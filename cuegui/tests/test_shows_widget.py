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


"""Tests for cuegui.ShowsWidget."""


import unittest

import mock
import qtpy.QtCore
import qtpy.QtWidgets

import opencue_proto.show_pb2
import opencue.wrappers.show

import cuegui.ShowsWidget
import cuegui.Style

from . import test_utils


@mock.patch('opencue.cuebot.Cuebot.getStub', new=mock.Mock())
class ShowsWidgetTests(unittest.TestCase):

    @mock.patch('opencue.api.getActiveShows', new=mock.Mock(return_value=[]))
    @mock.patch('opencue.cuebot.Cuebot.getStub', new=mock.Mock())
    def setUp(self):
        app = test_utils.createApplication()
        app.settings = qtpy.QtCore.QSettings()
        cuegui.Style.init()
        self.parentWidget = qtpy.QtWidgets.QWidget()
        self.showsWidget = cuegui.ShowsWidget.ShowsWidget(self.parentWidget)

    def test_slotsRunColumn(self):
        header = self.showsWidget.headerItem()
        headers = [header.text(col) for col in range(self.showsWidget.columnCount())]
        slots_col = headers.index('Slots Run')
        self.assertEqual(headers.index('Cores Run') + 1, slots_col)

        show = opencue.wrappers.show.Show(opencue_proto.show_pb2.Show(
            name='show1', show_stats=opencue_proto.show_pb2.ShowStats(reserved_slots=3)))
        item = cuegui.ShowsWidget.ShowWidgetItem(show, self.showsWidget)
        self.assertEqual(3, item.data(slots_col, qtpy.QtCore.Qt.DisplayRole))


if __name__ == '__main__':
    unittest.main()
