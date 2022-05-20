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

package com.wolt.osm.parallelpbf.entity;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

import java.util.LinkedList;
import java.util.List;

/**
 * OSM Way entity.
 *
 * Way is a ordered, therefore directed, collection of nodes.
 *
 * @see Node
 */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public final class Way extends OsmEntity {
    /**
     * Constructs Way setting mandatory fields.
     * @param id Required object id.
     */
    public Way(final long id) {
        setId(id);
    }

    /**
     * Ordered list of nodes, making way. Should contain at least one node.
     *
     * @see Node
     */
    private final List<Long> nodes = new LinkedList<>();
}
