package com.winlator.alsaserver;

import com.winlator.container.Container;
import com.winlator.sysvshm.SysVSharedMemory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class ALSAClient {
    static {
        System.loadLibrary("winlator");
    }

    private int bufferSize;
    private byte frameBytes;
    private int position;
    private ByteBuffer sharedBuffer;
    private ByteBuffer auxBuffer;
    private DataType dataType = DataType.U8;
    private byte channels = 2;
    private int sampleRate = 0;
    private String containerVariant;
    private long streamPtr = 0;
    private boolean playing = false;

    public enum DataType {
        U8(1),
        S16LE(2),
        S16BE(2),
        FLOATLE(4),
        FLOATBE(4);

        public final byte byteCount;

        DataType(int byteCount) {
            this.byteCount = (byte) byteCount;
        }
    }

    public ALSAClient(String containerVariant) {
        this.containerVariant = containerVariant;
    }

    public void release() {
        ByteBuffer byteBuffer = this.sharedBuffer;
        if (byteBuffer != null) {
            SysVSharedMemory.unmapSHMSegment(byteBuffer, byteBuffer.capacity());
            this.sharedBuffer = null;
            this.auxBuffer = null;
        }
        if (streamPtr != 0) {
            stop(streamPtr);
            close(streamPtr);
            streamPtr = 0;
        }
        playing = false;
    }

    public void prepare() {
        this.position = 0;
        this.frameBytes = (byte) (this.channels * this.dataType.byteCount);
        release();
        if (!isValidBufferSize()) return;
        streamPtr = create(dataType.ordinal(), channels, sampleRate, bufferSize);
        if (streamPtr != 0) {
            start(streamPtr);
            playing = true;
        }
    }

    public void start() {
        if (streamPtr != 0 && !playing) {
            start(streamPtr);
            playing = true;
        }
    }

    public void stop() {
        if (streamPtr != 0 && playing) {
            stop(streamPtr);
            playing = false;
        }
    }

    public void pause() {
        if (streamPtr != 0) {
            pause(streamPtr);
            playing = false;
        }
    }

    public void drain() {
        if (streamPtr != 0) flush(streamPtr);
    }

    /**
     * Write PCM data to the AAudio stream. Called from {@link ALSARequestHandler} for both glibc
     * and non-glibc paths. Keeps the method name from the old AudioTrack implementation so the
     * request handler doesn't need to change.
     */
    public void writeDataToTrack(ByteBuffer data) {
        if (!playing || streamPtr == 0) return;
        if (dataType == DataType.S16LE || dataType == DataType.FLOATLE) {
            data.order(ByteOrder.LITTLE_ENDIAN);
        } else if (dataType == DataType.S16BE || dataType == DataType.FLOATBE) {
            data.order(ByteOrder.BIG_ENDIAN);
        }
        int numFrames = data.limit() / frameBytes;
        int framesWritten = write(streamPtr, data, numFrames);
        if (framesWritten > 0) {
            position += framesWritten;
        }
        data.rewind();
    }

    /** Returns the playback head in frames. Used by Wine's glibc ALSA client for sync. */
    public int pointer() {
        return position;
    }

    public void setDataType(DataType dataType) {
        this.dataType = dataType;
    }

    public void setContainerVariant(String containerVariant) {
        this.containerVariant = containerVariant;
    }

    public String getContainerVariant() {
        return containerVariant;
    }

    public boolean isGlibc() {
        return containerVariant.equals(Container.GLIBC);
    }

    public void setChannels(int channels) {
        this.channels = (byte) channels;
    }

    public void setSampleRate(int sampleRate) {
        this.sampleRate = sampleRate;
    }

    public void setBufferSize(int bufferSize) {
        this.bufferSize = bufferSize;
    }

    public ByteBuffer getSharedBuffer() {
        return this.sharedBuffer;
    }

    public void setSharedBuffer(ByteBuffer sharedBuffer) {
        if (sharedBuffer != null) {
            ByteBuffer allocateDirect = ByteBuffer.allocateDirect(getBufferSizeInBytes());
            this.auxBuffer = allocateDirect.order(ByteOrder.LITTLE_ENDIAN);
            this.sharedBuffer = sharedBuffer.order(ByteOrder.LITTLE_ENDIAN);
        } else {
            this.auxBuffer = null;
            this.sharedBuffer = null;
        }
    }

    public ByteBuffer getAuxBuffer() {
        return this.auxBuffer;
    }

    public int getBufferSizeInBytes() {
        return this.bufferSize * this.frameBytes;
    }

    /**
     * Returns the minimum ALSA period buffer size in bytes for Wine to use.
     * Computed as a 40 ms window at the given sample rate, rounded up to the nearest 256 frames.
     * Replaces the old {@code latencyMillisToBufferSize} which depended on the removed Options class.
     */
    public static int minBufferSizeInBytes(int channels, DataType dataType, int sampleRate) {
        int frameBytes = channels * dataType.byteCount;
        // Round up to the next multiple of 256 frames so Wine's period aligns with typical
        // hardware burst boundaries.
        int frames = ((sampleRate * 40 / 1000) + 255) & ~255;
        return frames * frameBytes;
    }

    private boolean isValidBufferSize() {
        return this.bufferSize > 0;
    }

    private native long create(int format, byte channelCount, int sampleRate, int bufferSize);
    private native int write(long streamPtr, ByteBuffer buffer, int numFrames);
    private native void start(long streamPtr);
    private native void stop(long streamPtr);
    private native void pause(long streamPtr);
    private native void flush(long streamPtr);
    private native void close(long streamPtr);
}
