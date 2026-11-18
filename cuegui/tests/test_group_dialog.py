#  Copyright (c) OpenCue Project Authors
#
#  Licensed under the Apache License, Version 2.0 (the "License");
#  you may not use this file except in compliance with the License.
#  You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
#  Unless required by applicable law or agreed to in writing, software
#  distributed under the License is distributed on an "AS IS" BASIS,
#  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#  See the License for the specific language governing permissions and
#  limitations under the License.


"""Tests for cuegui.GroupDialog."""


import unittest

import mock

import qtpy.QtCore

import opencue_proto.job_pb2
import opencue.wrappers.group

import cuegui.GroupDialog
import cuegui.Style

from . import test_utils


@mock.patch('opencue.api.getDepartmentNames', new=mock.Mock(return_value=['Unknown']))
@mock.patch('opencue.cuebot.Cuebot.getStub', new=mock.Mock())
class GroupDialogTests(unittest.TestCase):
    # pylint: disable=protected-access

    def setUp(self):
        app = test_utils.createApplication()
        app.settings = qtpy.QtCore.QSettings()
        cuegui.Style.init()

    @staticmethod
    def _group(**fields):
        # The other limits as Cuebot sends them when unset, so saving leaves them alone.
        group = opencue.wrappers.group.Group(opencue_proto.job_pb2.Group(
            id='group-id', name='group-name', department='Unknown', default_job_priority=-1,
            default_job_min_cores=-1, default_job_max_cores=-1, max_cores=-1,
            default_job_min_gpus=-1, default_job_max_gpus=-1, max_gpus=-1, **fields))
        group.setMaxSlots = mock.Mock()
        return group

    def _modifyDialog(self, group):
        with mock.patch('opencue.api.getGroup', return_value=group):
            return cuegui.GroupDialog.ModifyGroupDialog(group)

    def test_setsMaxSlots(self):
        group = self._group(max_slots=-1)
        dialog = self._modifyDialog(group)
        # Unlimited starts unchecked.
        self.assertFalse(dialog._maxSlotsCheck.isChecked())

        dialog._maxSlotsCheck.setChecked(True)
        dialog._maxSlotsValue.setValue(6)
        dialog.accept()

        group.setMaxSlots.assert_called_once_with(6)

    def test_setsZeroMaxSlots(self):
        # 0 is a limit of its own: it rejects all slot work.
        group = self._group(max_slots=-1)
        dialog = self._modifyDialog(group)

        dialog._maxSlotsCheck.setChecked(True)
        dialog._maxSlotsValue.setValue(0)
        dialog.accept()

        group.setMaxSlots.assert_called_once_with(0)

    def test_uncheckingMaxSlotsRemovesTheLimit(self):
        group = self._group(max_slots=4)
        dialog = self._modifyDialog(group)
        self.assertTrue(dialog._maxSlotsCheck.isChecked())
        self.assertEqual(4, dialog._maxSlotsValue.value())

        dialog._maxSlotsCheck.setChecked(False)
        dialog.accept()

        group.setMaxSlots.assert_called_once_with(-1)

    def test_unchangedMaxSlotsIsNotSet(self):
        group = self._group(max_slots=4)
        dialog = self._modifyDialog(group)

        dialog.accept()

        group.setMaxSlots.assert_not_called()

    def test_groupFromCuebotWithoutSlotsHasNoMaxSlots(self):
        # A Cuebot that predates slot-based booking doesn't send the limit.
        group = self._group()
        dialog = self._modifyDialog(group)
        self.assertIsNone(dialog._maxSlotsCheck)

        dialog.accept()

        group.setMaxSlots.assert_not_called()

    def test_newGroupSetsMaxSlots(self):
        parent = self._group(max_slots=-1)
        child = self._group(max_slots=-1)
        parent.createSubGroup = mock.Mock(return_value=child)
        dialog = cuegui.GroupDialog.NewGroupDialog(parent)
        self.assertFalse(dialog._maxSlotsCheck.isChecked())

        dialog._nameValue.setText('child-name')
        dialog._maxSlotsCheck.setChecked(True)
        dialog._maxSlotsValue.setValue(3)
        dialog.accept()

        child.setMaxSlots.assert_called_once_with(3)

    def test_newGroupFromCuebotWithoutSlotsHasNoMaxSlots(self):
        dialog = cuegui.GroupDialog.NewGroupDialog(self._group())

        self.assertIsNone(dialog._maxSlotsCheck)


if __name__ == '__main__':
    unittest.main()
