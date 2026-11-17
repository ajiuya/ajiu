package com.aicode.feature.agent.domain.memory

import java.util.Locale
import javax.inject.Inject

class ProjectMemorySessionCoordinator @Inject constructor(
    private val journal: ProjectMemoryJournal,
    private val resumes: ProjectMemoryResumeResolver
) {
    data class Selection(val projectId: String?, val context: String?, val allowUnboundDiscovery: Boolean = true)
    data class NewSessionSelection(val projectId: String?)

    fun selectNewSession(identity: ProjectMemoryIdentity, inheritFrom: String? = null): NewSessionSelection {
        val project = when {
            journal.hasWorkspaceProjectBinding(identity) -> journal.workspaceProject(identity)
            inheritFrom != null && journal.hasSessionProjectBinding(inheritFrom, identity) -> journal.sessionProject(inheritFrom, identity)
            else -> null
        }
        return NewSessionSelection(project?.id)
    }

    fun initializeNewSession(sessionId: String, identity: ProjectMemoryIdentity, inheritFrom: String? = null) {
        journal.bindSessionProject(sessionId, identity, selectNewSession(identity, inheritFrom).projectId)
    }

    fun resolve(
        sessionId: String,
        identity: ProjectMemoryIdentity,
        request: String,
        explicitRequest: Boolean,
        inheritedProjectId: String? = null,
        persistBinding: Boolean = true,
        newSessionSelection: NewSessionSelection? = null
    ): Selection {
        val hasBinding = journal.hasSessionProjectBinding(sessionId, identity)
        if (explicitRequest) {
            ProjectMemoryResumeResolver.query(request)?.let { query ->
                val matches = ProjectMemoryIdentityResolver.matchingCandidates(identity, journal.findProjects(query))
                if (matches.size == 1) {
                    val project = matches.single()
                    if (persistBinding) journal.bindSessionProject(sessionId, identity, project.id)
                    return Selection(project.id, resumes.resolve(request, identity, project.id), false)
                }
                if (matches.size > 1 || !isCurrentProjectReference(query)) {
                    return Selection(null, resumes.resolve(request, identity), false)
                }
            }
        }
        if (hasBinding) {
            val project = journal.sessionProject(sessionId, identity)
            return Selection(project?.id, project?.let { resumes.resolve(request, identity, it.id) }, false)
        }
        if (newSessionSelection != null) {
            val id = newSessionSelection.projectId
            if (persistBinding) journal.bindSessionProject(sessionId, identity, id)
            return Selection(id, id?.let { resumes.resolve(request, identity, it) }, false)
        }
        if (inheritedProjectId != null) {
            val context = resumes.resolve(request, identity, inheritedProjectId)
            if (persistBinding) journal.bindSessionProject(sessionId, identity, inheritedProjectId)
            return Selection(inheritedProjectId, context, false)
        }
        return Selection(null, null, true)
    }

    private fun isCurrentProjectReference(query: String): Boolean {
        val value = query.trim().lowercase(Locale.ROOT)
        return listOf("上次", "上个", "上回", "之前", "当前", "本项目", "这个项目", "该项目", "last ", "previous ", "current ", "this project")
            .any { value.startsWith(it) }
    }
}
