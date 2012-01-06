/**
 * Copyright (C) 2011 Akiban Technologies Inc.
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License, version 3,
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see http://www.gnu.org/licenses.
 */

package com.persistit;

import java.util.Arrays;

import org.junit.Test;

import com.persistit.exception.CorruptVolumeException;
import com.persistit.exception.PersistitException;
import com.persistit.unit.PersistitUnitTestCase;

public class CorruptVolumeTest extends PersistitUnitTestCase {

    private String _volumeName = "persistit";

    @Test
    public void testCorruptVolume() throws PersistitException {
        Exchange exchange = _persistit.getExchange(_volumeName, "CorruptVolumeTest", true);
        // store some records
        exchange.getValue().put("CorruptVolumeTest");
        for (int i = 0; i < 10000; i++) {
            exchange.to(i).store();
        }
        // Corrupt the volume by zonking the the index page
        final Buffer buffer = exchange.getBufferPool().get(exchange.getVolume(), 4, true, true);
        Arrays.fill(buffer.getBytes(), 20, 200, (byte) 0);
        buffer.setDirtyAtTimestamp(_persistit.getTimestampAllocator().updateTimestamp());
        buffer.releaseTouched();
        //
        try {
            exchange.to(9000).fetch();
            fail("Should have gotten a CorruptVolumeException");
        } catch (CorruptVolumeException e) {
            System.out.println(e.getMessage());
            e.printStackTrace();
        }

    }

}
