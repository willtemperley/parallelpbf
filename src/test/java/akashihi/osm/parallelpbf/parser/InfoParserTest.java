/*
 * This file is part of parallelpbf.
 *
 *     parallelpbf is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     Foobar is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with Foobar.  If not, see <https://www.gnu.org/licenses/>.
 */

package akashihi.osm.parallelpbf.parser;

import akashihi.osm.parallelpbf.TestObjectsFactory;
import crosby.binary.Osmformat;
import lombok.var;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

@Tag("BaseParser")
class InfoParserTest {

    class InfoParser extends BaseParser<Object, Consumer<Object>> {

        public InfoParser(Consumer<Object> callback, Osmformat.StringTable stringTable) {
            super(callback, stringTable);
        }

        @Override
        protected void parse(Object message) { }
    }

    @Test
    void testNodeInfoMissing() {
        var node = Osmformat.Node.newBuilder()
                .setId(1)
                .setLat(2)
                .setLon(3)
                .build();

        var testedObject = new InfoParser(null, TestObjectsFactory.stringTable);

        assertNull(testedObject.parseInfo(node));
    }

    @Test
    void testNodeInfo() {
        var node = Osmformat.Node.newBuilder()
                .setId(1)
                .setLat(2)
                .setLon(3)
                .setInfo(TestObjectsFactory.infoMessage)
                .build();

        var testedObject = new InfoParser(null, TestObjectsFactory.stringTable);

        var actual = testedObject.parseInfo(node);
        assertEquals(TestObjectsFactory.info, actual);
    }

    @Test
    void testWayInfoMissing() {
        var way = Osmformat.Way.newBuilder()
                .setId(1)
                .build();

        var testedObject = new InfoParser(null, TestObjectsFactory.stringTable);

        assertNull(testedObject.parseInfo(way));
    }

    @Test
    void testWayInfo() {
        var way = Osmformat.Way.newBuilder()
                .setId(1)
                .setInfo(TestObjectsFactory.infoMessage)
                .build();

        var testedObject = new InfoParser(null, TestObjectsFactory.stringTable);

        var actual = testedObject.parseInfo(way);
        assertEquals(TestObjectsFactory.info, actual);
    }

    @Test
    void testRelationInfoMissing() {
        var relation = Osmformat.Relation.newBuilder()
                .setId(1)
                .build();

        var testedObject = new InfoParser(null, TestObjectsFactory.stringTable);

        assertNull(testedObject.parseInfo(relation));
    }

    @Test
    void testRelationInfo() {
        var relation = Osmformat.Relation.newBuilder()
                .setId(1)
                .setInfo(TestObjectsFactory.infoMessage)
                .build();

        var testedObject = new InfoParser(null, TestObjectsFactory.stringTable);

        var actual = testedObject.parseInfo(relation);
        assertEquals(TestObjectsFactory.info, actual);
    }
}