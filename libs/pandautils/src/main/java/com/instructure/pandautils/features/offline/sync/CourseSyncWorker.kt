/*
 * Copyright (C) 2023 - present Instructure, Inc.
 *
 *     Licensed under the Apache License, Version 2.0 (the "License");
 *     you may not use this file except in compliance with the License.
 *     You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *     Unless required by applicable law or agreed to in writing, software
 *     distributed under the License is distributed on an "AS IS" BASIS,
 *     WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *     See the License for the specific language governing permissions and
 *     limitations under the License.
 *
 *
 */

package com.instructure.pandautils.features.offline.sync

import android.content.Context
import android.net.Uri
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.instructure.canvasapi2.apis.*
import com.instructure.canvasapi2.builders.RestParams
import com.instructure.canvasapi2.models.*
import com.instructure.canvasapi2.utils.ApiPrefs
import com.instructure.canvasapi2.utils.DataResult
import com.instructure.canvasapi2.utils.depaginate
import com.instructure.pandautils.features.offline.offlinecontent.CourseFileSharedRepository
import com.instructure.pandautils.room.offline.daos.*
import com.instructure.pandautils.room.offline.entities.*
import com.instructure.pandautils.room.offline.facade.*
import com.instructure.pandautils.room.offline.model.CourseSyncSettingsWithFiles
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.io.File
import java.lang.IllegalStateException
import java.util.Date

@HiltWorker
class CourseSyncWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted private val workerParameters: WorkerParameters,
    private val courseApi: CourseAPI.CoursesInterface,
    private val pageApi: PageAPI.PagesInterface,
    private val userApi: UserAPI.UsersInterface,
    private val assignmentApi: AssignmentAPI.AssignmentInterface,
    private val calendarEventApi: CalendarEventAPI.CalendarEventInterface,
    private val courseSyncSettingsDao: CourseSyncSettingsDao,
    private val pageFacade: PageFacade,
    private val userFacade: UserFacade,
    private val courseFacade: CourseFacade,
    private val assignmentFacade: AssignmentFacade,
    private val quizDao: QuizDao,
    private val quizApi: QuizAPI.QuizInterface,
    private val scheduleItemFacade: ScheduleItemFacade,
    private val conferencesApi: ConferencesApi.ConferencesInterface,
    private val conferenceFacade: ConferenceFacade,
    private val discussionApi: DiscussionAPI.DiscussionInterface,
    private val discussionTopicHeaderFacade: DiscussionTopicHeaderFacade,
    private val announcementApi: AnnouncementAPI.AnnouncementInterface,
    private val moduleApi: ModuleAPI.ModuleInterface,
    private val moduleFacade: ModuleFacade,
    private val featuresApi: FeaturesAPI.FeaturesInterface,
    private val courseFeaturesDao: CourseFeaturesDao,
    private val courseFileSharedRepository: CourseFileSharedRepository,
    private val fileFolderDao: FileFolderDao,
    private val discussionTopicFacade: DiscussionTopicFacade,
    private val groupApi: GroupAPI.GroupInterface,
    private val groupFacade: GroupFacade,
    private val enrollmentsApi: EnrollmentAPI.EnrollmentInterface,
    private val courseSyncProgressDao: CourseSyncProgressDao,
    private val htmlParser: HtmlParser,
    private val fileFolderApi: FileFolderAPI.FilesFoldersInterface,
    private val pageDao: PageDao,
    private val firebaseCrashlytics: FirebaseCrashlytics,
    private val fileSync: FileSync
) : CoroutineWorker(context, workerParameters) {

    private val additionalFileIdsToSync = mutableSetOf<Long>()
    private val externalFilesToSync = mutableSetOf<String>()

    private var courseId = -1L

    override suspend fun doWork(): Result {
        val courseSettingsWithFiles =
            courseSyncSettingsDao.findWithFilesById(inputData.getLong(COURSE_ID, -1)) ?: return Result.failure()
        val courseSettings = courseSettingsWithFiles.courseSyncSettings
        courseId = courseSettings.courseId
        val course = fetchCourseDetails(courseId)

        initProgress(courseSettings, course)

        if (courseSettings.fullFileSync || courseSettingsWithFiles.files.isNotEmpty()) {
            fetchFiles(courseId)
        }

        coroutineScope {
            val filesDeferred = async { fileSync.syncFiles(courseSettingsWithFiles.courseSyncSettings) }
            val contentDeferred = async { fetchCourseContent(courseSettingsWithFiles, course) }

            listOf(contentDeferred, filesDeferred).awaitAll()
        }

        fileSync.syncAdditionalFiles(courseSettings, additionalFileIdsToSync, externalFilesToSync)

        val progress = courseSyncProgressDao.findByCourseId(courseId)
        progress
            ?.copy(progressState = if (progress.tabs.any { it.value.state == ProgressState.ERROR }) ProgressState.ERROR else ProgressState.COMPLETED)
            ?.let {
                courseSyncProgressDao.update(it)
            }

        return Result.success()
    }

    private suspend fun fetchCourseContent(courseSettingsWithFiles: CourseSyncSettingsWithFiles, course: Course) {
        val courseSettings = courseSettingsWithFiles.courseSyncSettings
        if (courseSettings.isTabSelected(Tab.PAGES_ID)) {
            fetchPages(course.id)
        } else {
            pageFacade.deleteAllByCourseId(course.id)
        }

        // We need to do this after the pages request because we delete all the previous pages there
        val isHomeTabAPage = Tab.FRONT_PAGE_ID == course.homePageID
        if (isHomeTabAPage) {
            fetchHomePage(course.id)
        }

        if (courseSettings.areAnyTabsSelected(setOf(Tab.ASSIGNMENTS_ID, Tab.GRADES_ID, Tab.SYLLABUS_ID))) {
            fetchAssignments(course.id)
        } else {
            assignmentFacade.deleteAllByCourseId(course.id)
        }

        if (courseSettings.isTabSelected(Tab.SYLLABUS_ID)) {
            fetchSyllabus(course.id)
        } else {
            scheduleItemFacade.deleteAllByCourseId(course.id)
        }

        if (courseSettings.isTabSelected(Tab.CONFERENCES_ID)) {
            fetchConferences(course.id)
        } else {
            conferenceFacade.deleteAllByCourseId(course.id)
        }

        if (courseSettings.isTabSelected(Tab.DISCUSSIONS_ID)) {
            fetchDiscussions(course.id)
        } else {
            discussionTopicHeaderFacade.deleteAllByCourseId(course.id, false)
        }

        if (courseSettings.isTabSelected(Tab.ANNOUNCEMENTS_ID)) {
            fetchAnnouncements(course.id)
        } else {
            discussionTopicHeaderFacade.deleteAllByCourseId(course.id, true)
        }

        if (courseSettings.isTabSelected(Tab.PEOPLE_ID)) {
            fetchUsers(course.id)
        }

        if (courseSettings.isTabSelected(Tab.QUIZZES_ID)) {
            fetchAllQuizzes(CanvasContext.Type.COURSE.apiString, course.id)
        } else if (!courseSettings.areAnyTabsSelected(setOf(Tab.ASSIGNMENTS_ID, Tab.GRADES_ID, Tab.SYLLABUS_ID))) {
            quizDao.deleteAllByCourseId(course.id)
        }

        if (courseSettings.isTabSelected(Tab.MODULES_ID)) {
            fetchModules(course.id, courseSettingsWithFiles)
        } else {
            moduleFacade.deleteAllByCourseId(course.id)
        }
    }

    private suspend fun fetchSyllabus(courseId: Long) {
        fetchTab(Tab.SYLLABUS_ID) {
            val calendarEvents = fetchCalendarEvents(courseId)
            val assignmentEvents = fetchCalendarAssignments(courseId)
            val scheduleItems = mutableListOf<ScheduleItem>()

            scheduleItems.addAll(calendarEvents)
            scheduleItems.addAll(assignmentEvents)

            scheduleItemFacade.insertScheduleItems(scheduleItems, courseId)
        }
    }

    private suspend fun fetchCalendarEvents(courseId: Long): List<ScheduleItem> {
        val restParams = RestParams(usePerPageQueryParam = true, isForceReadFromNetwork = true)
        val calendarEvents = calendarEventApi.getCalendarEvents(
            true,
            CalendarEventAPI.CalendarEventType.CALENDAR.apiName,
            null,
            null,
            listOf("course_$courseId"),
            restParams
        ).depaginate {
            calendarEventApi.next(it, restParams)
        }.dataOrThrow

        calendarEvents.forEach { it.description = parseHtmlContent(it.description, courseId) }

        return calendarEvents
    }

    private suspend fun fetchCalendarAssignments(courseId: Long): List<ScheduleItem> {
        val restParams = RestParams(usePerPageQueryParam = true, isForceReadFromNetwork = true)
        val calendarAssignments = calendarEventApi.getCalendarEvents(
            true,
            CalendarEventAPI.CalendarEventType.ASSIGNMENT.apiName,
            null,
            null,
            listOf("course_$courseId"),
            restParams
        ).depaginate {
            calendarEventApi.next(it, restParams)
        }.dataOrThrow

        calendarAssignments.forEach { it.description = parseHtmlContent(it.description, courseId) }

        return calendarAssignments
    }

    private suspend fun fetchPages(courseId: Long) {
        fetchTab(Tab.PAGES_ID) {
            val params = RestParams(usePerPageQueryParam = true, isForceReadFromNetwork = true)
            val pages = pageApi.getFirstPagePagesWithBody(courseId, CanvasContext.Type.COURSE.apiString, params)
                .depaginate { nextUrl ->
                    pageApi.getNextPagePagesList(nextUrl, params)
                }.dataOrThrow

            pages.forEach {
                it.body = parseHtmlContent(it.body, courseId)
            }

            pageFacade.insertPages(pages, courseId)
        }
    }

    private suspend fun fetchHomePage(courseId: Long) {
        try {
            val frontPage = pageApi.getFrontPage(
                CanvasContext.Type.COURSE.apiString,
                courseId,
                RestParams(isForceReadFromNetwork = true)
            ).dataOrNull
            if (frontPage != null) {
                frontPage.body = parseHtmlContent(frontPage.body, courseId)
                pageFacade.insertPage(frontPage, courseId)
            }
        } catch (e: Exception) {
            firebaseCrashlytics.recordException(e)
        }
    }

    private suspend fun fetchAssignments(courseId: Long) {
        fetchTab(Tab.ASSIGNMENTS_ID, Tab.GRADES_ID) {
            val restParams = RestParams(usePerPageQueryParam = true, isForceReadFromNetwork = true)
            val assignmentGroups = assignmentApi.getFirstPageAssignmentGroupListWithAssignments(courseId, restParams)
                .depaginate { nextUrl ->
                    assignmentApi.getNextPageAssignmentGroupListWithAssignments(nextUrl, restParams)
                }.dataOrThrow

            assignmentGroups.forEach { group ->
                group.assignments.forEach {
                    it.description = parseHtmlContent(it.description, courseId)
                    it.discussionTopicHeader?.message = parseHtmlContent(it.discussionTopicHeader?.message, courseId)
                }
            }

            fetchQuizzes(assignmentGroups, courseId)

            assignmentFacade.insertAssignmentGroups(assignmentGroups, courseId)
        }
    }

    private suspend fun fetchCourseDetails(courseId: Long): Course {
        val params = RestParams(isForceReadFromNetwork = true)
        val course = courseApi.getFullCourseContent(courseId, params).dataOrThrow
        val enrollments = course.enrollments.orEmpty().flatMap {
            enrollmentsApi.getEnrollmentsForUserInCourse(courseId, it.userId, params).dataOrThrow
        }.toMutableList()

        course.syllabusBody = parseHtmlContent(course.syllabusBody, courseId)

        courseFacade.insertCourse(course.copy(enrollments = enrollments))

        val courseFeatures = featuresApi.getEnabledFeaturesForCourse(courseId, params).dataOrNull
        courseFeatures?.let {
            courseFeaturesDao.insert(CourseFeaturesEntity(courseId, it))
        }

        return course
    }

    private suspend fun fetchUsers(courseId: Long) {
        fetchTab(Tab.PEOPLE_ID) {
            val restParams = RestParams(usePerPageQueryParam = true, isForceReadFromNetwork = true)
            val users = userApi.getFirstPagePeopleList(courseId, CanvasContext.Type.COURSE.apiString, restParams)
                .depaginate { userApi.getNextPagePeopleList(it, restParams) }.dataOrThrow

            userFacade.insertUsers(users, courseId)
        }
    }

    private suspend fun fetchQuizzes(assignmentGroups: List<AssignmentGroup>, courseId: Long) {
        val params = RestParams(isForceReadFromNetwork = true)
        val quizzes = mutableListOf<QuizEntity>()
        assignmentGroups.forEach { group ->
            group.assignments.forEach { assignment ->
                if (assignment.quizId != 0L) {
                    val quiz = quizApi.getQuiz(assignment.courseId, assignment.quizId, params).dataOrNull
                    quiz?.description = parseHtmlContent(quiz?.description, courseId)
                    quiz?.let { quizzes.add(QuizEntity(it, assignment.courseId)) }
                }
            }
        }
        quizDao.deleteAndInsertAll(quizzes, courseId)
    }

    private suspend fun fetchAllQuizzes(contextType: String, courseId: Long) {
        fetchTab(Tab.QUIZZES_ID) {
            val params = RestParams(usePerPageQueryParam = true, isForceReadFromNetwork = true)
            val quizzes = quizApi.getFirstPageQuizzesList(contextType, courseId, params).depaginate { nextUrl ->
                quizApi.getNextPageQuizzesList(nextUrl, params)
            }.dataOrThrow

            quizzes.forEach {
                it.description = parseHtmlContent(it.description, courseId)
            }

            quizDao.deleteAndInsertAll(quizzes.map { QuizEntity(it, courseId) }, courseId)
        }
    }

    private suspend fun fetchConferences(courseId: Long) {
        fetchTab(Tab.CONFERENCES_ID) {
            val conferences = getConferencesForContext(CanvasContext.emptyCourseContext(courseId), true).dataOrThrow

            conferenceFacade.insertConferences(conferences, courseId)
        }
    }

    private suspend fun getConferencesForContext(
        canvasContext: CanvasContext, forceNetwork: Boolean
    ): DataResult<List<Conference>> {
        val params = RestParams(usePerPageQueryParam = true, isForceReadFromNetwork = forceNetwork)

        return conferencesApi.getConferencesForContext(canvasContext.toAPIString().drop(1), params).map {
            it.conferences
        }.depaginate { url ->
            conferencesApi.getNextPage(url, params).map { it.conferences }
        }
    }

    private suspend fun fetchDiscussions(courseId: Long) {
        fetchTab(Tab.DISCUSSIONS_ID) {
            val params = RestParams(usePerPageQueryParam = true, isForceReadFromNetwork = true)
            val discussions =
                discussionApi.getFirstPageDiscussionTopicHeaders(CanvasContext.Type.COURSE.apiString, courseId, params)
                    .depaginate { nextPage -> discussionApi.getNextPage(nextPage, params) }.dataOrThrow

            discussions.forEach {
                it.message = parseHtmlContent(it.message, courseId)
            }

            discussionTopicHeaderFacade.insertDiscussions(discussions, courseId, false)

            fetchDiscussionDetails(discussions, courseId)
        }
    }

    private suspend fun fetchAnnouncements(courseId: Long) {
        fetchTab(Tab.ANNOUNCEMENTS_ID) {
            val params = RestParams(usePerPageQueryParam = true, isForceReadFromNetwork = true)
            val announcements =
                announcementApi.getFirstPageAnnouncementsList(CanvasContext.Type.COURSE.apiString, courseId, params)
                    .depaginate { nextPage ->
                        announcementApi.getNextPageAnnouncementsList(
                            nextPage,
                            params
                        )
                    }.dataOrThrow

            announcements.forEach {
                it.message = parseHtmlContent(it.message, courseId)
            }

            discussionTopicHeaderFacade.insertDiscussions(announcements, courseId, true)
        }
    }

    private suspend fun fetchDiscussionDetails(discussions: List<DiscussionTopicHeader>, courseId: Long) {
        val params = RestParams(isForceReadFromNetwork = true)
        discussions.forEach { discussionTopicHeader ->
            val discussionTopic = discussionApi.getFullDiscussionTopic(
                CanvasContext.Type.COURSE.apiString,
                courseId,
                discussionTopicHeader.id,
                1,
                params
            ).dataOrNull
            discussionTopic?.let {
                val topic = parseDiscussionTopicHtml(it, courseId)
                discussionTopicFacade.insertDiscussionTopic(discussionTopicHeader.id, topic)
            }
        }

        val groups = groupApi.getFirstPageGroups(params)
            .depaginate { nextUrl -> groupApi.getNextPageGroups(nextUrl, params) }.dataOrNull

        groups?.let {
            it.forEach { group ->
                ApiPrefs.user?.let { groupFacade.insertGroupWithUser(group, it) }
            }
        }
    }

    private suspend fun parseDiscussionTopicHtml(discussionTopic: DiscussionTopic, courseId: Long): DiscussionTopic {
        discussionTopic.views.map { parseHtmlContent(it.message, courseId) }
        discussionTopic.views.map { it.replies?.map { parseDiscussionEntryHtml(it, courseId) } }
        return discussionTopic
    }

    private suspend fun parseDiscussionEntryHtml(discussionEntry: DiscussionEntry, courseId: Long): DiscussionEntry {
        discussionEntry.message = parseHtmlContent(discussionEntry.message, courseId)
        discussionEntry.replies?.map { parseDiscussionEntryHtml(it, courseId) }
        return discussionEntry
    }

    private suspend fun fetchModules(courseId: Long, courseSettings: CourseSyncSettingsWithFiles) {
        fetchTab(Tab.MODULES_ID) {
            val params = RestParams(usePerPageQueryParam = true, isForceReadFromNetwork = true)
            val moduleObjects = moduleApi.getFirstPageModuleObjects(
                CanvasContext.Type.COURSE.apiString, courseId, params
            ).depaginate { nextPage ->
                moduleApi.getNextPageModuleObjectList(nextPage, params)
            }.dataOrThrow.map { moduleObject ->
                val moduleItems = moduleApi.getFirstPageModuleItems(
                    CanvasContext.Type.COURSE.apiString,
                    courseId,
                    moduleObject.id,
                    params
                ).depaginate { nextPage ->
                    moduleApi.getNextPageModuleItemList(nextPage, params)
                }.dataOrNull ?: moduleObject.items
                moduleObject.copy(items = moduleItems)
            }

            moduleFacade.insertModules(moduleObjects, courseId)

            val moduleItems = moduleObjects.flatMap { it.items }
            moduleItems.forEach {
                when (it.type) {
                    ModuleItem.Type.Page.name -> fetchPageModuleItem(courseId, it, params)
                    ModuleItem.Type.File.name -> fetchFileModuleItem(courseId, it, params, courseSettings)
                    ModuleItem.Type.Quiz.name -> fetchQuizModuleItem(courseId, it, params)
                }
            }
        }
    }

    private suspend fun fetchTab(vararg tabIds: String, fetchBlock: suspend () -> Unit) {
        if (isStopped) return
        try {
            fetchBlock()
            updateTabSuccess(*tabIds)
        } catch (e: Exception) {
            e.printStackTrace()
            updateTabError(*tabIds)
            firebaseCrashlytics.recordException(e)
        }
    }

    private suspend fun fetchPageModuleItem(
        courseId: Long,
        it: ModuleItem,
        params: RestParams
    ) {
        if (it.pageUrl != null && pageDao.findByUrl(it.pageUrl!!) == null) {
            val page = pageApi.getDetailedPage(courseId, it.pageUrl!!, params).dataOrNull
            page?.body = parseHtmlContent(page?.body, courseId)
            page?.let { pageFacade.insertPage(it, courseId) }
        }
    }

    private suspend fun fetchFileModuleItem(
        courseId: Long,
        it: ModuleItem,
        params: RestParams,
        courseSettings: CourseSyncSettingsWithFiles
    ) {
        val fileId = it.contentId
        if (courseSettings.files.any { it.id == fileId }) return // File is selected for sync so we don't need to sync it

        val file = fileFolderApi.getCourseFile(courseId, it.contentId, params).dataOrNull
        if (file?.id != null) {
            additionalFileIdsToSync.add(file.id)
        }
    }

    private suspend fun fetchQuizModuleItem(
        courseId: Long,
        it: ModuleItem,
        params: RestParams
    ) {
        if (quizDao.findById(it.contentId) == null) {
            val quiz = quizApi.getQuiz(courseId, it.contentId, params).dataOrNull
            quiz?.description = parseHtmlContent(quiz?.description, courseId)
            quiz?.let { quizDao.insert(QuizEntity(it, courseId)) }
        }
    }

    private suspend fun parseHtmlContent(htmlContent: String?, courseId: Long): String? {
        val htmlParsingResult = htmlParser.createHtmlStringWithLocalFiles(htmlContent, courseId)
        additionalFileIdsToSync.addAll(htmlParsingResult.internalFileIds)
        externalFilesToSync.addAll(htmlParsingResult.externalFileUrls)
        return htmlParsingResult.htmlWithLocalFileLinks
    }

    private suspend fun fetchFiles(courseId: Long) {
        val fileFolders = courseFileSharedRepository.getCourseFoldersAndFiles(courseId)

        val entities = fileFolders.map { FileFolderEntity(it) }
        fileFolderDao.replaceAll(entities, courseId)
    }

    private suspend fun initProgress(
        courseSettings: CourseSyncSettingsEntity,
        course: Course
    ) {
        val availableTabs = course.tabs?.map { it.tabId } ?: emptyList()
        val selectedTabs = courseSettings.tabs.filter { availableTabs.contains(it.key) && it.value == true }.keys
        val progress = (courseSyncProgressDao.findByCourseId(course.id) ?: createNewProgress(courseSettings))
            .copy(
                tabs = selectedTabs.associateWith { tabId ->
                    TabSyncData(
                        course.tabs?.find { it.tabId == tabId }?.label ?: tabId,
                        ProgressState.IN_PROGRESS
                    )
                }
            )

        courseSyncProgressDao.update(progress)
    }

    private suspend fun createNewProgress(courseSettings: CourseSyncSettingsEntity): CourseSyncProgressEntity {
        val newProgress = CourseSyncProgressEntity(
            workerId = workerParameters.id.toString(),
            courseId = courseSettings.courseId,
            courseName = courseSettings.courseName,
            progressState = ProgressState.STARTING,
        )
        courseSyncProgressDao.insert(newProgress)
        return newProgress
    }

    private suspend fun updateTabError(vararg tabIds: String) {
        val progress = courseSyncProgressDao.findByCourseId(courseId)
        progress?.copy(
            tabs = progress.tabs.toMutableMap().apply {
                tabIds.forEach { tabId ->
                    get(tabId)?.copy(state = ProgressState.ERROR)?.let {
                        put(tabId, it)
                    }
                }

            },
        )?.let {
            courseSyncProgressDao.update(it)
        }
    }

    private suspend fun updateTabSuccess(vararg tabIds: String) {
        val progress = courseSyncProgressDao.findByCourseId(courseId)
        progress?.copy(
            tabs = progress.tabs.toMutableMap().apply {
                tabIds.forEach { tabId ->
                    get(tabId)?.copy(state = ProgressState.COMPLETED)?.let {
                        put(tabId, it)
                    }
                }
            },
        )?.let {
            courseSyncProgressDao.update(it)
        }
    }

    companion object {
        const val COURSE_ID = "course_id"
        const val TAG = "CourseSyncWorker"

        fun createOnTimeWork(courseId: Long, wifiOnly: Boolean): OneTimeWorkRequest {
            val data = workDataOf(COURSE_ID to courseId)
            return OneTimeWorkRequestBuilder<CourseSyncWorker>()
                .addTag(TAG)
                .setInputData(data)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .build()
        }
    }
}