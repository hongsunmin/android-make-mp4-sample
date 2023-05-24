package kr.pe.makemp4sample;

import android.media.MediaCodec.BufferInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.util.Log;

import java.io.IOException;
import java.nio.ByteBuffer;

public class VideoWriter {

    private static final String TAG = "MakeMP4Sample";

    private MediaMuxer muxer;
    private int trackIndex;

    private int frameIndex;

    public VideoWriter(String outputFile, MediaFormat format) {
        try {
            muxer = new MediaMuxer(outputFile, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            trackIndex = muxer.addTrack(format);
            frameIndex = 1;
            muxer.start();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void write(ByteBuffer byteBuf, BufferInfo bufferInfo) {
        bufferInfo.presentationTimeUs = computePresentationTime(frameIndex);

        Log.d(TAG,
                "mux frame: " + frameIndex +
                        " size: " + bufferInfo.size +
                        " PTS: " + bufferInfo.presentationTimeUs
        );
        muxer.writeSampleData(trackIndex, byteBuf, bufferInfo);
        ++frameIndex;
    }

    public void stop() {
        muxer.stop();
        muxer.release();
    }

    private static long computePresentationTime(int frameIndex) {
        return frameIndex * 1000000 / 30;
    }
}
