package com.instructure.canvasapi2.di

import com.instructure.canvasapi2.apis.HelpLinksAPI
import com.instructure.canvasapi2.managers.CourseManager
import com.instructure.canvasapi2.managers.GroupManager
import com.instructure.canvasapi2.managers.HelpLinksManager
import com.instructure.canvasapi2.utils.ApiPrefs
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ApiModule {

    @Provides
    fun provideCourseManager(): CourseManager {
        return CourseManager
    }

    @Provides
    fun provideGroupManager(): GroupManager {
        return GroupManager
    }

    @Provides
    fun provideHelpLinksManager(helpLinksApi: HelpLinksAPI): HelpLinksManager {
        return HelpLinksManager(helpLinksApi)
    }

    @Provides
    @Singleton
    fun provideHelpLinksApi(): HelpLinksAPI {
        return HelpLinksAPI
    }

    @Provides
    @Singleton
    fun provideApiPrefs(): ApiPrefs {
        return ApiPrefs
    }
}