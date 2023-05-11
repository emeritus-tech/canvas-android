/*
 * Copyright (C) 2018 - present Instructure, Inc.
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, version 3 of the License.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 */
package com.emeritus.student.holders

import androidx.recyclerview.widget.RecyclerView
import android.view.View
import com.emeritus.student.R

class EditFavoritesCourseHeaderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
    companion object {
        const val HOLDER_RES_ID: Int = R.layout.viewholder_course_header_favorites
    }
}
