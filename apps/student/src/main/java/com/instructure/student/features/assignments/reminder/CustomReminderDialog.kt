/*
 * Copyright (C) 2024 - present Instructure, Inc.
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

package com.instructure.student.features.assignments.reminder

import android.app.Dialog
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.viewModels
import com.instructure.pandautils.utils.ThemePrefs
import com.instructure.student.R
import com.instructure.student.databinding.DialogCustomReminderBinding
import com.instructure.student.features.assignments.details.AssignmentDetailsViewModel
import com.instructure.student.features.assignments.details.ReminderChoice
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class CustomReminderDialog : DialogFragment() {

    private lateinit var binding: DialogCustomReminderBinding
    private val parentViewModel: AssignmentDetailsViewModel by viewModels(ownerProducer = {
        requireParentFragment()
    })

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        binding = DialogCustomReminderBinding.inflate(layoutInflater, null, false)

        return AlertDialog.Builder(requireContext())
            .setView(binding.root)
            .setTitle(R.string.customReminderTitle)
            .setPositiveButton(R.string.done) { _, _ ->
                val quantity = binding.quantity.text.toString().toIntOrNull() ?: return@setPositiveButton
                when (binding.choices.checkedRadioButtonId) {
                    R.id.minutes -> parentViewModel.onReminderSelected(ReminderChoice.Minute(quantity))
                    R.id.hours -> parentViewModel.onReminderSelected(ReminderChoice.Hour(quantity))
                    R.id.days -> parentViewModel.onReminderSelected(ReminderChoice.Day(quantity))
                    R.id.weeks -> parentViewModel.onReminderSelected(ReminderChoice.Week(quantity))
                }
            }
            .create().apply {
                setOnShowListener {
                    getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(ThemePrefs.textButtonColor)
                }
            }
    }

    companion object {
        fun newInstance() = CustomReminderDialog()
    }
}