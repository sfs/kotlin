/*
 * Copyright 2010-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.gradle.plugin.cocoapods

import org.gradle.api.Named
import org.gradle.api.NamedDomainObjectSet
import org.gradle.api.Project
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Optional

open class CocoapodsExtension(private val project: Project) {
    @get:Input
    val version: String
        get() = project.version.toString()

    /**
     * Configure authors of the pod built from this project.
     */
    @Optional
    @Input
    var authors: String? = null

    /**
     * Configure license of the pod built from this project.
     */
    @Optional
    @Input
    var license: String? = null

    /**
     * Configure description of the pod built from this project.
     */
    @Optional
    @Input
    var summary: String? = null

    /**
     * Configure homepage of the pod built from this project.
     */
    @Optional
    @Input
    var homepage: String? = null

    private val pods_ = project.container(CocoapodsDependency::class.java)

    /**
     * Returns a list of pod dependencies.
     */
    @get:Input
    val pods: NamedDomainObjectSet<CocoapodsDependency>
        get() = pods_

    /**
     * Add a Cocoapods dependency to the pod built from this project.
     */
    @JvmOverloads
    fun pod(name: String, version: String? = null, moduleName: String = name) {
        check(pods_.findByName(name) == null) { "Project already has a Cocoapods dependency with name $name" }
        pods_.add(CocoapodsDependency(name, version, moduleName))
    }

    data class CocoapodsDependency(
        private val name: String,
        val version: String?,
        val moduleName: String
    ) : Named, java.io.Serializable {
        override fun getName(): String = name
    }

}