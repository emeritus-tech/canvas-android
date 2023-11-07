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
 */

package com.instructure.pandautils.room.offline.daos

import androidx.room.*
import com.instructure.pandautils.room.offline.entities.QuizEntity

@Dao
interface QuizDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: QuizEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<QuizEntity>)

    @Query("DELETE FROM QuizEntity WHERE courseId = :courseId")
    suspend fun deleteAllByCourseId(courseId: Long)

    @Transaction
    suspend fun deleteAndInsertAll(entities: List<QuizEntity>, courseId: Long) {
        deleteAllByCourseId(courseId)
        insertAll(entities)
    }

    @Delete
    suspend fun delete(entity: QuizEntity)

    @Update
    suspend fun update(entity: QuizEntity)

    @Query("SELECT * FROM QuizEntity WHERE id = :id")
    suspend fun findById(id: Long): QuizEntity?

    @Query("SELECT * FROM QuizEntity WHERE courseId = :courseId")
    suspend fun findByCourseId(courseId: Long): List<QuizEntity>
}