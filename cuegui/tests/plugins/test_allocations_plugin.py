#  Copyright Contributors to the OpenCue Project
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


"""Tests for cuegui.plugins.AllocationsPlugin."""


import unittest

import mock
import qtpy.QtCore
import qtpy.QtWidgets

import opencue_proto.facility_pb2
import opencue.wrappers.allocation

import cuegui.plugins.AllocationsPlugin
import cuegui.Style
from .. import test_utils


@mock.patch('opencue.cuebot.Cuebot.getStub', new=mock.Mock())
class MonitorAllocationsTests(unittest.TestCase):

    @mock.patch('opencue.api.getAllocations', new=mock.Mock(return_value=[]))
    @mock.patch('opencue.cuebot.Cuebot.getStub', new=mock.Mock())
    def setUp(self):
        app = test_utils.createApplication()
        app.settings = qtpy.QtCore.QSettings()
        cuegui.Style.init()
        self.parentWidget = qtpy.QtWidgets.QWidget()
        self.monitorAllocations = cuegui.plugins.AllocationsPlugin.MonitorAllocations(
            self.parentWidget)

    def test_slotColumns(self):
        header = self.monitorAllocations.headerItem()
        headers = [header.text(col) for col in range(self.monitorAllocations.columnCount())]
        slots_col = headers.index('Slots')
        # The first Repair column counts cores; the slot columns follow the core columns.
        self.assertEqual(headers.index('Repair') + 1, slots_col)
        self.assertEqual(slots_col + 1, headers.index('Slots Idle'))

        alloc = opencue.wrappers.allocation.Allocation(opencue_proto.facility_pb2.Allocation(
            name='lax.general',
            stats=opencue_proto.facility_pb2.AllocationStats(slots=8, available_slots=3)))
        item = cuegui.plugins.AllocationsPlugin.AllocationWidgetItem(
            alloc, self.monitorAllocations)
        self.assertEqual([8, 3], [item.data(col, qtpy.QtCore.Qt.DisplayRole)
                                  for col in (slots_col, slots_col + 1)])


if __name__ == '__main__':
    unittest.main()
