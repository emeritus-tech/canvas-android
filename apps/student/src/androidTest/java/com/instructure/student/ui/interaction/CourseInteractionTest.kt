/*
 * Copyright (C) 2019 - present Instructure, Inc.
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
 */
package com.instructure.student.ui.interaction

import com.instructure.canvas.espresso.Stub
import com.instructure.panda_annotations.FeatureCategory
import com.instructure.panda_annotations.Priority
import com.instructure.panda_annotations.TestCategory
import com.instructure.panda_annotations.TestMetaData
import com.instructure.student.ui.utils.StudentTest
import org.junit.Test

class CourseInteractionTest : StudentTest() {
    override fun displaysPageObjects() = Unit // Not used for interaction tests

    @Stub
    @Test
    @TestMetaData(Priority.P1, FeatureCategory.COURSE, TestCategory.INTERACTION, true)
    fun testCourse_linkFromCoursePageToPublicCoursePage() {
        // Link from a course page to another public course should open in the app
    }

    @Stub
    @Test
    @TestMetaData(Priority.P0, FeatureCategory.COURSE, TestCategory.INTERACTION, true, FeatureCategory.FILES)
    fun testCourse_openFile() {
        // user should be able to open/preview course file
    }

}