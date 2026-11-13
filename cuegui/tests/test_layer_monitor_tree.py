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


"""Tests for cuegui.LayerMonitorTree."""


import unittest

import mock
import qtpy.QtCore
import qtpy.QtWidgets

import opencue_proto.job_pb2
import opencue.wrappers.layer

import cuegui.LayerMonitorTree
import cuegui.Style

from . import test_utils


@mock.patch('opencue.cuebot.Cuebot.getStub', new=mock.Mock())
class LayerMonitorTreeTests(unittest.TestCase):

    @mock.patch('opencue.cuebot.Cuebot.getStub', new=mock.Mock())
    def setUp(self):
        app = test_utils.createApplication()
        app.settings = qtpy.QtCore.QSettings()
        cuegui.Style.init()
        self.parentWidget = qtpy.QtWidgets.QWidget()
        self.layerMonitorTree = cuegui.LayerMonitorTree.LayerMonitorTree(self.parentWidget)

    def test_slotsColumn(self):
        header = self.layerMonitorTree.headerItem()
        headers = [header.text(col) for col in range(self.layerMonitorTree.columnCount())]
        slots_col = headers.index('Slots')
        self.assertEqual(headers.index('Cores') + 1, slots_col)

        for slots_required, shown in ((2, 2), (0, '-')):
            layer = opencue.wrappers.layer.Layer(
                opencue_proto.job_pb2.Layer(name='layer1', slots_required=slots_required))
            item = cuegui.LayerMonitorTree.LayerWidgetItem(layer, self.layerMonitorTree)
            self.assertEqual(shown, item.data(slots_col, qtpy.QtCore.Qt.DisplayRole))


if __name__ == '__main__':
    unittest.main()
