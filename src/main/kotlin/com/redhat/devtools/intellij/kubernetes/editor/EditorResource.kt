/*******************************************************************************
 * Copyright (c) 2023 Red Hat, Inc.
 * Distributed under license by Red Hat, Inc. All rights reserved.
 * This program is made available under the terms of the
 * Eclipse Public License v2.0 which accompanies this distribution,
 * and is available at http://www.eclipse.org/legal/epl-v20.html
 *
 * Contributors:
 * Red Hat, Inc. - initial API and implementation
 ******************************************************************************/
package com.redhat.devtools.intellij.kubernetes.editor

import com.intellij.openapi.diagnostic.logger
import com.redhat.devtools.intellij.kubernetes.model.IResourceModel
import com.redhat.devtools.intellij.kubernetes.model.IResourceModelListener
import com.redhat.devtools.intellij.kubernetes.model.context.IActiveContext
import com.redhat.devtools.intellij.kubernetes.model.util.areEqual
import com.redhat.devtools.intellij.kubernetes.model.util.hasGenerateName
import com.redhat.devtools.intellij.kubernetes.model.util.hasName
import com.redhat.devtools.intellij.kubernetes.model.util.isSameResource
import com.redhat.devtools.intellij.kubernetes.model.util.toKindAndName
import com.redhat.devtools.intellij.kubernetes.model.util.toMessage
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.client.KubernetesClient
import io.fabric8.kubernetes.client.utils.KubernetesResourceUtil
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

open class EditorResource(
    /** for testing purposes */
    private var resource: HasMetadata,
    private val resourceModel: IResourceModel,
    private val resourceChangedListener: IResourceModelListener?,
    // for mocking purposes
    private val clusterResourceFactory: (resource: HasMetadata, context: IActiveContext<out HasMetadata, out KubernetesClient>?) -> ClusterResource? =
        ClusterResource.Factory::create
) {
    var disposed: Boolean = false
    /** for testing purposes */
    private var state: EditorResourceState? = null
    /** for testing purposes */
    protected open val clusterResource: ClusterResource? = createClusterResource(resource)

    private var lastPushedPulled: HasMetadata? = null
    private var resourceVersion: String? = resource.metadata.resourceVersion

    private val resourceChangeMutex = ReentrantLock()

    /**
     * Sets the resource to this instance. Only modified versions of the same resource are processed.
     * Will do nothing if the given resource is a different resource in name, namespace, kind etc.
     *
     * @param new the new resource that should be set to this editor resource
     *
     * @see isSameResource
     */
    fun setResource(new: HasMetadata) {
        resourceChangeMutex.withLock {
            val existing = this.resource
            if (new.isSameResource(existing)
                && !areEqual(new, existing)) {
                this.resource = new
            }
        }
    }

    /**
     * Returns the resource that is edited.
     *
     * @return the resource that is edited
     */
    fun getResource(): HasMetadata {
        return resourceChangeMutex.withLock {
            resource
        }
    }

    fun getResourceOnCluster(): HasMetadata? {
        return clusterResource?.pull(true)
    }

    /** for testing purposes */
    protected open fun setState(state: EditorResourceState?) {
        resourceChangeMutex.withLock {
            this.state = state
        }
    }

    /**
     * Returns the state of this editor resource.
     *
     * @return the state of this editor resource.
     */
    fun getState(): EditorResourceState {
        return resourceChangeMutex.withLock {
            val state = getState(resource, state)
            this.state = state
            state
        }
    }

    private fun getState(resource: HasMetadata, existingState: EditorResourceState?): EditorResourceState {
        val isModified = isModified(resource)
        val isOutdated = isOutdatedVersion()
        val existsOnCluster = existsOnCluster()
        return when {
            !isConnected() ->
                Error("Error contacting cluster. Make sure it's reachable, current context set, etc.", null as String?)

            !hasName(resource)
                    && !hasGenerateName(resource) ->
                Error("Resource has neither name nor generateName.", null as String?)

            existingState is Error
                    && !isModified->
                existingState

            isDeleted() ->
                DeletedOnCluster()

            isModified
                || !existsOnCluster ->
                Modified(
                    existsOnCluster,
                    isOutdated
                )

            isOutdated ->
                Outdated()

            existingState != null ->
                existingState

            else ->
                Identical()
        }
    }

    fun pull(): EditorResourceState {
        val state = try {
            val cluster = clusterResource
                ?: return Error("Could not pull ${toKindAndName(resource)}", "cluster not connected.")
            val pulled = cluster.pull(true)
                ?: return Error("Could not pull ${toKindAndName(resource)}", "resource not found on cluster.")
            setResource(pulled)
            setResourceVersion(KubernetesResourceUtil.getResourceVersion(pulled))
            setLastPushedPulled(pulled)
            Pulled()
        } catch (e: Exception) {
            /**
             * Store resource that we tried to push but failed.
             * In this way this resource is not in modified state anymore
             * @see [isModified]
             */
            setLastPushedPulled(resource)
            logger<ResourceEditor>().warn(e)
            Error(
                "Could not pull  ${toKindAndName(resource)}",
                toMessage(e.cause)
            )
        }
        setState(state)
        return state
    }

    fun push(): EditorResourceState {
        val state = try {
            val cluster = clusterResource
                ?: return Error(
                    "Could not push ${toKindAndName(resource)} to cluster.",
                    "Not connected."
                )
            val updatedResource = cluster.push(resource)
            setResourceVersion(KubernetesResourceUtil.getResourceVersion(updatedResource))
            /**
             * Store resource that was pushed, not resource returned from cluster.
             * In this way this resource is not in modified state anymore
             */
            setLastPushedPulled(resource)
            createPushedState(resource, cluster.exists())
        } catch (e: Exception) {
            /**
             * Store resource that we tried to push but failed.
             * In this way this resource is not in modified state anymore
             * @see [isModified]
             */
            setLastPushedPulled(resource)
            Error("Could not push ${toKindAndName(resource)} to cluster.", e)
        }
        setState(state)
        return state
    }

    private fun createPushedState(resource: HasMetadata?, exists: Boolean): EditorResourceState {
        return if (resource != null) {
            if (exists) {
                Updated()
            } else {
                Created()
            }
        } else {
            Error(
                "Could not push resource to cluster.",
                "No resource present."
            )
        }
    }

    private fun setResourceVersion(version: String?) {
        resourceChangeMutex.withLock {
            this.resourceVersion = version
        }
    }

    /** for testing purposes */
    protected open fun getResourceVersion(): String? {
        return resourceChangeMutex.withLock {
            this.resourceVersion
        }
    }

    /** for testing purposes */
    protected open fun setLastPushedPulled(resource: HasMetadata?) {
        resourceChangeMutex.withLock {
            lastPushedPulled = resource
        }
    }

    /** for testing purposes */
    protected open fun getLastPushedPulled(): HasMetadata? {
        return resourceChangeMutex.withLock {
            lastPushedPulled
        }
    }

    fun isOutdatedVersion(): Boolean {
        val version =  resourceChangeMutex.withLock {
            resourceVersion
        }
        return true == clusterResource?.isOutdatedVersion(version)
    }

    /**
     * Returns `true` if the given resource has changes that don't exist in the resource
     * that was last pulled/pushed from/to the cluster.
     * The following properties are not taken into account:
     * * [io.fabric8.kubernetes.api.model.ObjectMeta.resourceVersion]
     * * [io.fabric8.kubernetes.api.model.ObjectMeta.uid]
     *
     * @return true if the resource is not equal to the resource that was pulled from the cluster
     */
    private fun isModified(resource: HasMetadata): Boolean {
        return !areEqual(resource, getLastPushedPulled())
    }

    private fun isDeleted(): Boolean {
        return true == clusterResource?.isDeleted()
    }

    private fun existsOnCluster(): Boolean {
        return true == clusterResource?.exists()
    }

    private fun isConnected(): Boolean {
        return clusterResource != null
    }

    fun watch() {
        clusterResource?.watch()
    }

    fun stopWatch() {
        clusterResource?.stopWatch()
    }

    fun dispose() {
        if (disposed) {
            return
        }
        this.disposed = true
        clusterResource?.close()
        setLastPushedPulled(null)
        setResourceVersion(null)
    }

    private fun createClusterResource(resource: HasMetadata): ClusterResource? {
        val context = resourceModel.getCurrentContext()
        return if (context != null) {
            val clusterResource = clusterResourceFactory.invoke(
                resource,
                context
            )

            val resourceChangeListener = resourceChangedListener
            if (resourceChangeListener != null) {
                clusterResource?.addListener(resourceChangeListener)
            }
            clusterResource?.watch()
            clusterResource
        } else {
            null
        }
    }

}