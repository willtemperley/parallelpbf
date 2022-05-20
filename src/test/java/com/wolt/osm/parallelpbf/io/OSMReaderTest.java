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

package com.wolt.osm.parallelpbf.io;

import com.google.protobuf.ByteString;
import crosby.binary.Fileformat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.Semaphore;
import java.util.zip.Deflater;

import static org.junit.jupiter.api.Assertions.*;

class OSMReaderTest {
    private static final String testString = "TestString";

    private Semaphore limiter;

    @BeforeEach
    void setUp() {
        limiter = new Semaphore(0);
    }

    private static class TestReader extends OSMReader {
        TestReader(byte[] blobValue, Semaphore tasksLimiterValue) {
            super(blobValue, tasksLimiterValue);
        }

        @Override
        protected void read(byte[] message) {
            assertEquals(testString, new String(message, StandardCharsets.UTF_8));
        }
    }

    @Test
    void testRaw() {
        byte[] content = testString.getBytes(StandardCharsets.UTF_8);
        var blob = Fileformat.Blob.newBuilder()
                .setRaw(ByteString.copyFrom(content))
                .build().toByteArray();

        var testedObject = new TestReader(blob, limiter);
        testedObject.run();
        assertEquals(1, limiter.availablePermits());
    }

    @Test
    void testZlib() {
        byte[] content = testString.getBytes(StandardCharsets.UTF_8);
        Deflater deflater = new Deflater();
        deflater.setInput(content);
        deflater.finish();
        byte[] compressed_content = new byte[content.length * 2];
        int compressedDataLength = deflater.deflate(compressed_content);
        var blob = Fileformat.Blob.newBuilder()
                .setRawSize(content.length)
                .setZlibData(ByteString.copyFrom(compressed_content, 0, compressedDataLength))
                .build().toByteArray();

        var testedObject = new TestReader(blob, limiter);
        testedObject.run();
        assertEquals(1, limiter.availablePermits());
    }

    @Test
    void testZlibInvalidRawSize() {
        byte[] content = testString.getBytes(StandardCharsets.UTF_8);
        Deflater deflater = new Deflater();
        deflater.setInput(content);
        deflater.finish();
        byte[] compressed_content = new byte[content.length * 2];
        int compressedDataLength = deflater.deflate(compressed_content);
        var blob = Fileformat.Blob.newBuilder()
                .setRawSize(9000)
                .setZlibData(ByteString.copyFrom(compressed_content, 0, compressedDataLength))
                .build().toByteArray();

        var testedObject = new TestReader(blob, limiter);
        assertThrows(RuntimeException.class, testedObject::run);
    }

    @Test
    void testZlibInvalidData() {
        var blob = Fileformat.Blob.newBuilder()
                .setRawSize(1)
                .setZlibData(ByteString.copyFromUtf8("test"))
                .build().toByteArray();

        var testedObject = new TestReader(blob, limiter);
        assertThrows(RuntimeException.class, testedObject::run);
    }

    @Test
    void testInvalidCompressionFormat() {
        var blob = Fileformat.Blob.newBuilder()
                .setRawSize(9000)
                .setOBSOLETEBzip2Data(ByteString.copyFromUtf8(""))
                .build().toByteArray();

        var testedObject = new TestReader(blob, limiter);
        assertThrows(RuntimeException.class, testedObject::run);
    }

    @Test
    void testInvalidBlobFormat() {
        var testedObject = new TestReader("fail".getBytes(StandardCharsets.UTF_8), limiter);
        assertThrows(RuntimeException.class, testedObject::run);
    }
}