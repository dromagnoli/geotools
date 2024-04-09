/*
 *    GeoTools - The Open Source Java GIS Toolkit
 *    http://geotools.org
 *
 *    (C) 2024, Open Source Geospatial Foundation (OSGeo)
 *
 *    This library is free software; you can redistribute it and/or
 *    modify it under the terms of the GNU Lesser General Public
 *    License as published by the Free Software Foundation;
 *    version 2.1 of the License.
 *
 *    This library is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *    Lesser General Public License for more details.
 */
package org.geotools.io;

import java.io.IOException;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import org.geotools.imageio.netcdf.utilities.NetCDFUtilities;
import org.geotools.util.NIOUtilities;
import ucar.nc2.util.cache.FileCacheIF;
import ucar.unidata.io.KMPMatch;
import ucar.unidata.io.RandomAccessFile;
import ucar.unidata.util.StringUtil2;

/**
 * A ReadOnly MemoryMapped RandomAccessFile. It will map the file in memory, using a
 * MappedByteBuffer.
 *
 * <p>Writing capability is not supported at the moment.
 */
public class MemoryMappedRandomAccessFile extends RandomAccessFile {

    private static final int BUFFER_MEMORY_LIMIT;

    private static final int MAX_SEARCH_BUFFER_LIMIT;

    private FileChannel channel;

    enum CacheUsage {
        NOT_IN_CACHE,
        IN_USE,
        NOT_IN_USE
    }

    private CacheUsage cacheUsage = CacheUsage.NOT_IN_USE;

    static {
        long limit = Integer.MAX_VALUE;
        String memoryMappingLimit =
                System.getProperty("org.geotools.coverage.io.netcdf.memorymaplimit");
        if (memoryMappingLimit != null && !memoryMappingLimit.isEmpty()) {
            limit = Long.parseLong(memoryMappingLimit);
            // The mapped byte buffer cannot be bigger than 2GB.
            if (limit < 0 || limit > Integer.MAX_VALUE) {
                limit = Integer.MAX_VALUE;
            }
        }
        BUFFER_MEMORY_LIMIT = (int) limit;
        MAX_SEARCH_BUFFER_LIMIT = Math.min(16384, BUFFER_MEMORY_LIMIT);
    }

    private MappedByteBuffer mappedByteBuffer;

    private static final int DEFAULT_BUFFER_SIZE = 1;

    private long currentOffset = 0;
    private FileChannel.MapMode mapMode;
    public long id = 0;

    public MemoryMappedRandomAccessFile(String location, String mode, long id) throws IOException {
        super(MemoryMappedRandomAccessFile.getUriString(location), mode, DEFAULT_BUFFER_SIZE);
        if (!readonly) reportReadOnly();
        initDataBuffer();
        cacheUsage = CacheUsage.IN_USE;
        this.id = id;
    }

    private void initDataBuffer() throws IOException {
        channel = file.getChannel();
        mapMode = FileChannel.MapMode.READ_ONLY;
        long channelSize = channel.size();
        dataSize = (int) channelSize;
        dataEnd = channelSize;
        debugLeaks = NetCDFUtilities.DEBUG_CACHE;
        showOpen = NetCDFUtilities.DEBUG_CACHE;
        showRead = NetCDFUtilities.DEBUG_CACHE;

        long initialMapSize =
                (channelSize - channel.position()) < (long) BUFFER_MEMORY_LIMIT
                        ? channelSize
                        : BUFFER_MEMORY_LIMIT;
        mappedByteBuffer = channel.map(mapMode, 0, initialMapSize);
        mappedByteBuffer.position((int) channel.position());
        bufferStart = 0;
        filePosition = 0;
        endOfFile = false;
    }

    @Override
    public void seek(long pos) throws IOException {
        long jumpSize = pos - filePosition;
        filePosition = pos;
        if (filePosition < 0) {
            System.out.println("Negative filePosition on seek: " + filePosition + "for " + this);
        }
        if (filePosition < dataEnd) {
            if (filePosition >= currentOffset + BUFFER_MEMORY_LIMIT
                    || filePosition < currentOffset) {
                // If we are going outside of the currently mapped portion
                // we need to remap the buffer
                bufferCheck(jumpSize);
            } else {
                mappedByteBuffer.position((int) (filePosition - currentOffset));
            }
            endOfFile = false;
        } else {
            endOfFile = true;
        }
    }

    @SuppressWarnings("deprecation") // we need to use it to mark the internal cacheState
    @Override
    public void release() {
        super.release();
        cacheUsage = CacheUsage.NOT_IN_USE;
    }

    @SuppressWarnings("deprecation") // we need to use it to mark the internal cacheState
    @Override
    public void reacquire() {
        super.reacquire();
        if (debugLeaks) {
            // System.out.println("reacquire " + this);
        }
        cacheUsage = CacheUsage.IN_USE;
    }

    @SuppressWarnings("deprecation") // we need to use it to mark the internal cacheState
    @Override
    public synchronized void setFileCache(FileCacheIF fileCache) {
        super.setFileCache(fileCache);
        if (fileCache == null) {
            cacheUsage = CacheUsage.NOT_IN_CACHE;
        }
    }

    @Override
    public int read() throws IOException {
        bufferCheck(1);
        if (filePosition < dataEnd) {
            ++filePosition;
            if (filePosition < 0) {
                System.out.println(
                        "Negative filePosition on read: " + filePosition + "for " + this);
            }
            return mappedByteBuffer.get() & 0xff;
        } else {
            return -1;
        }
    }

    private void bufferCheck(long readSize) throws IOException {
        if (mappedByteBuffer.remaining() < readSize || readSize < 0) {
            long fcPosition = filePosition;
            if (fcPosition < 0) {
                System.out.println(
                        "Negative filePosition on buffercheck: " + fcPosition + "for " + this);
            }
            if (dataEnd > fcPosition + BUFFER_MEMORY_LIMIT) {
                currentOffset = fcPosition;
            } else {
                currentOffset = dataEnd - BUFFER_MEMORY_LIMIT;
                // Can't go before the beginning of the file
                if (currentOffset < 0) {
                    currentOffset = 0;
                }
            }
            int newPosition = (int) (fcPosition - currentOffset);
            System.out.println(
                    "cleaning and remapping bytebuffer for "
                            + this
                            + " fcPosition: "
                            + Long.toString(fcPosition)
                            + " currentOffset:"
                            + currentOffset
                            + " newPosition:"
                            + newPosition);
            NIOUtilities.clean(mappedByteBuffer);
            mappedByteBuffer = channel.map(mapMode, currentOffset, BUFFER_MEMORY_LIMIT);
            // make sure to properly re-align the position
            mappedByteBuffer.position(newPosition);
        }
    }

    @Override
    public int readBytes(byte[] dst, int offset, int length) throws IOException {
        if (endOfFile) return -1;
        length = (int) Math.min(length, dataEnd - filePosition);
        bufferCheck(length);
        if (length > 0) {
            if (filePosition < 0) {
                System.out.println(
                        "Negative filePosition before readbytes: " + filePosition + "for " + this);
            }
            mappedByteBuffer.get(dst, offset, length);
            filePosition += length;
            if (filePosition < 0) {
                System.out.println(
                        "Negative filePosition after readbytes: " + filePosition + "for " + this);
            }
            if (filePosition == dataEnd) {
                endOfFile = true;
            }
        }
        return length;
    }

    @Override
    public void write(int b) {
        reportReadOnly();
    }

    @Override
    public void writeBytes(byte[] dst, int offset, int length) {
        reportReadOnly();
    }

    private void reportReadOnly() {
        throw new UnsupportedOperationException("Read Only MemoryMappedRandomAccessFile");
    }

    @Override
    public long length() {
        return dataEnd;
    }

    @SuppressWarnings("PMD.SystemPrintln") // low level leaks debug as done in the RAF class
    @Override
    public synchronized void close() throws IOException {
        FileCacheIF cache = getGlobalFileCache();
        if (cache != null && cacheUsage != CacheUsage.NOT_IN_CACHE) {
            if (cacheUsage != CacheUsage.IN_USE) {
                return;
            }

            cacheUsage = CacheUsage.NOT_IN_USE;
            if (cache.release(this)) {
                return;
            }
            this.cacheUsage = CacheUsage.NOT_IN_CACHE;
        }

        if (debugLeaks) {
            openFiles.remove(this.location);
            if (showOpen) {
                System.out.println("  close " + this);
            }
        }

        if (this.file != null) {
            this.flush();
            System.out.println("Closing java RAF for " + this);
            this.file.close();
            this.file = null;
        }
        if (channel != null && channel.isOpen()) {
            System.out.println("Closing Channel for " + this);
            channel.close();
        }
        if (mappedByteBuffer != null) {
            System.out.println("Cleaning MMAPBuffer for " + this);
            NIOUtilities.clean(mappedByteBuffer, true);
        }

        mappedByteBuffer = null;
        channel = null;
    }

    public static String getUriString(String uriString) {
        StringUtil2.replace(uriString, '\\', "/");
        if (uriString.startsWith("file:")) {
            uriString = StringUtil2.unescape(uriString.substring(5));
        }
        return uriString;
    }

    @Override
    public boolean searchForward(KMPMatch match, int maxBytes) throws IOException {
        System.out.println("SearchForward " + this);
        long start = this.getFilePointer();
        long last = maxBytes < 0 ? this.length() : Math.min(this.length(), start + (long) maxBytes);
        long needToScan = last - start;
        int bytesAvailable = (int) (this.dataEnd - this.filePosition);
        if (bytesAvailable < 1) {
            this.seek(this.filePosition);
            bytesAvailable = (int) (this.dataEnd - this.filePosition);
        }
        int scanBytes =
                (int) Math.min(Math.min(bytesAvailable, needToScan), MAX_SEARCH_BUFFER_LIMIT);
        byte[] tempBuffer = new byte[scanBytes];
        long startPosition = mappedByteBuffer.position() % BUFFER_MEMORY_LIMIT;
        readBytes(tempBuffer, 0, scanBytes);
        int pos = match.indexOf(tempBuffer, 0, scanBytes);
        long seekPos = last;
        if (pos >= 0) {
            seekPos = this.currentOffset + startPosition + (long) pos;
            this.seek(seekPos);
            return true;
        } else {
            int matchLen = match.getMatchLength();

            for (needToScan -= scanBytes; needToScan > (long) matchLen; needToScan -= scanBytes) {
                scanBytes =
                        (int)
                                Math.min(
                                        maxBytes > 0 ? maxBytes : MAX_SEARCH_BUFFER_LIMIT,
                                        needToScan);
                readBytes(tempBuffer, 0, scanBytes);
                pos = match.indexOf(tempBuffer, 0, scanBytes);
                if (pos > 0) {
                    seekPos =
                            this.currentOffset
                                    + (long) pos
                                    + (endOfFile ? BUFFER_MEMORY_LIMIT - scanBytes : 0);
                    this.seek(seekPos);
                    return true;
                }
            }

            this.seek(seekPos);
            return false;
        }
    }

    protected int read_(long pos, byte[] b, int offset, int len) throws IOException {
        System.out.println("invoking read_ with pos: " + pos);
        seek(pos);
        // this.file.seek(pos);

        // int n = this.file.read(b, offset, len);
        int n = read(b, offset, len);
        if (debugAccess) {
            if (showRead) {
                System.out.printf(
                        " **read_ %s = %d bytes at %d; block = %d%n",
                        this.location, len, pos, pos / (long) this.buffer.length);
            }

            // debug_nseeks.incrementAndGet();
            // debug_nbytes.addAndGet((long)len);
        }

        /*if (this.extendMode && n < len) {
            n = len;
        }*/

        return n;
    }

    @Override
    public int skipBytes(int n) throws IOException {
        seek(filePosition + (long) n);
        return n;
    }

    @Override
    public long skipBytes(long n) throws IOException {
        seek(filePosition + n);
        return n;
    }

    @Override
    public void unread() {
        try {
            seek(filePosition - 1);
        } catch (IOException e) {
            // Does nothing for
        }
    }

    @Override
    public String toString() {
        return this.location + "(" + id + ")";
    }
}
