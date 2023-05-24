package kr.pe.makemp4sample;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.media.MediaCodec;
import android.media.MediaCodec.BufferInfo;
import android.media.MediaFormat;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ProgressBar;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import com.google.android.material.button.MaterialButtonToggleGroup;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.util.Arrays;
import java.util.UUID;

public class MainActivity extends AppCompatActivity {

    enum CodeType { H264, H265 }

    private static final String TAG = "MakeMP4Sample";

    private static final int MAX_SAMPLE_SIZE = 256 * 1024;

    private byte[] vps;
    private byte[] sps;
    private byte[] pps;

    private VideoWriter videoWriter;

    private CodeType codeType = CodeType.H264;
    private Thread worker;
    private final int maxFrameCount = 360;
    private int frameCount = 0;

    private ProgressBar progressBar;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        verifyStoragePermissions(this);

        MaterialButtonToggleGroup toggleGroup = findViewById(R.id.codecTypeToggleGroup);
        toggleGroup.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (isChecked) {
                codeType = checkedId == R.id.h265CodecTypeButton ? CodeType.H265 : CodeType.H264;
            }
        });

        Button button = findViewById(R.id.makeMP4Button);
        button.setOnClickListener(v -> {
            worker = new Thread(() -> {
                changeProgressBarVisibility(true);
                initialize();
                if (codeType == CodeType.H265) {
                    processH265();
                } else {
                    processH264();
                }

                changeProgressBarVisibility(false);
            });
            worker.start();
        });

        progressBar = findViewById(R.id.progressBar);
        progressBar.setVisibility(View.GONE);
    }

    private void processH264() {
        String fileName = "test.h264";
        String externalPath = Environment.getExternalStorageDirectory().getAbsolutePath();
        // /storage/emulated/0/test.h264
        File externalFile = new File(externalPath, fileName);
        try (FileInputStream fis = new FileInputStream(externalFile)) {
            FileChannel fc = fis.getChannel();

            ByteBuffer buffer = ByteBuffer.allocate(MAX_SAMPLE_SIZE);
            int offset = 0;
            int bufferSize = 0;
            do {
                bufferSize = readNalData(fc, offset, buffer);
                byte type = (byte) (buffer.get(4) & 0x1F);
                Log.d(TAG, String.format("nalu offset %d, size:%d, %d", offset, bufferSize, type));
                switch (type) {
                    case 7:
                        sps = new byte[bufferSize];
                        buffer.get(sps);
                        break;
                    case 8:
                        pps = new byte[bufferSize];
                        buffer.get(pps);
                        if (videoWriter == null) {
                            String outputFile = String.format("%s.mp4", UUID.randomUUID());
                            File externalOutputFile = new File(externalPath, outputFile);
                            externalOutputFile.deleteOnExit();

                            MediaFormat format = MediaFormat.createVideoFormat(
                                    MediaFormat.MIMETYPE_VIDEO_AVC,
                                    1280,
                                    720
                            );
                            format.setByteBuffer("csd-0", ByteBuffer.wrap(sps));
                            format.setByteBuffer("csd-1", ByteBuffer.wrap(pps));
                            videoWriter = new VideoWriter(
                                    externalOutputFile.getAbsolutePath(),
                                    format
                            );
                        }
                        break;
                    case 5:
                    case 1:
                        BufferInfo bufferInfo = new BufferInfo();
                        bufferInfo.offset = 0;
                        bufferInfo.size = bufferSize;
                        if (type == 5) {
                            bufferInfo.flags = MediaCodec.BUFFER_FLAG_KEY_FRAME;
                        } else {
                            bufferInfo.flags = 0;
                        }

                        videoWriter.write(buffer, bufferInfo);
                        ++frameCount;
                        break;
                    default:
                        Log.e(TAG, String.format("Unprocessed types %d", type));
                        break;
                }

                offset += bufferSize;

                Thread.sleep(100);
            } while (bufferSize > 0 && frameCount < maxFrameCount);

            videoWriter.stop();
        } catch (IOException e) {
            e.printStackTrace();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    private void processH265() {
        String fileName = "test.hevc";
        String externalPath = Environment.getExternalStorageDirectory().getAbsolutePath();
        // /storage/emulated/0/test.hevc
        File externalFile = new File(externalPath, fileName);
        try (FileInputStream fis = new FileInputStream(externalFile)) {
            FileChannel fc = fis.getChannel();

            ByteBuffer buffer = ByteBuffer.allocate(MAX_SAMPLE_SIZE);
            buffer.order(ByteOrder.LITTLE_ENDIAN);
            int offset = 0;
            int bufferSize = 0;
            do {
                bufferSize = readNalData(fc, offset, buffer);
                short naluHeader = buffer.getShort(4);
                byte type = (byte) ((naluHeader & 0x7E) >> 1);
                Log.d(TAG, String.format("nalu offset %d, size:%d, %d", offset, bufferSize, type));
                switch (type) {
                    case 32:
                        vps = new byte[bufferSize];
                        buffer.get(vps);
                        break;
                    case 33:
                        sps = new byte[bufferSize];
                        buffer.get(sps);
                        break;
                    case 34:
                        pps = new byte[bufferSize];
                        buffer.get(pps);
                        if (videoWriter == null) {
                            String outputFile = String.format("%s.mp4", UUID.randomUUID());
                            File externalOutputFile = new File(externalPath, outputFile);
                            externalOutputFile.deleteOnExit();

                            MediaFormat format = MediaFormat.createVideoFormat(
                                    MediaFormat.MIMETYPE_VIDEO_HEVC,
                                    1280,
                                    720
                            );
                            ByteBuffer csdBuf = ByteBuffer.allocate(vps.length + sps.length + pps.length);
                            csdBuf.put(vps).put(sps).put(pps).rewind();
                            format.setByteBuffer("csd-0", csdBuf);
                            videoWriter = new VideoWriter(
                                    externalOutputFile.getAbsolutePath(),
                                    format
                            );
                        }
                        break;
                    case 20:
                    case 21:
                    case 1:
                        BufferInfo bufferInfo = new BufferInfo();
                        bufferInfo.offset = 0;
                        bufferInfo.size = bufferSize;
                        if (type == 20) {
                            bufferInfo.flags = MediaCodec.BUFFER_FLAG_KEY_FRAME;
                        } else {
                            bufferInfo.flags = 0;
                        }

                        videoWriter.write(buffer, bufferInfo);
                        ++frameCount;
                        break;
                    default:
                        Log.e(TAG, String.format("Unprocessed types %d", type));
                        break;
                }

                offset += bufferSize;

                Thread.sleep(100);
            } while (bufferSize > 0 && frameCount < maxFrameCount);

            videoWriter.stop();
        } catch (IOException e) {
            e.printStackTrace();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    private void initialize() {
        vps = sps = pps = null;
        videoWriter = null;
        frameCount = 0;
    }

    private int readNalData(FileChannel fc, int offset, ByteBuffer byteBuffer) throws IOException {
        byte[] nalStartCode = {0, 0, 0, 1};

        int bufferCap = 512 * 1024;
        ByteBuffer buffer = ByteBuffer.allocate(bufferCap);
        fc.position(offset);
        int readSize = fc.read(buffer);
        int packetSize = -1;

        int bufferBegin = 4;
        int bufferEnd = readSize - 1;
        while (bufferBegin <= bufferEnd) {
            if (buffer.get(bufferBegin) == 0x01) {
                byte[] tmp = new byte[4];
                buffer.position(bufferBegin - 3);
                buffer.get(tmp);
                if (Arrays.equals(tmp, nalStartCode)) {
                    packetSize = bufferBegin - 3;
                    Log.d(TAG, String.format("nalu size is %d", packetSize));
                    byte[] data = new byte[packetSize];
                    buffer.position(0);
                    buffer.get(data);
                    byteBuffer.position(0);
                    byteBuffer.put(data);
                    byteBuffer.position(0);
                    break;
                }
            }

            ++bufferBegin;
        }
        return packetSize;
    }

    private void changeProgressBarVisibility(boolean visibility) {
        runOnUiThread(() -> {
            if (visibility) {
                progressBar.setVisibility(View.VISIBLE);
                getWindow().setFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
            } else {
                progressBar.setVisibility(View.GONE);
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
            }
        });
    }

    // Storage Permissions
    private static final int REQUEST_EXTERNAL_STORAGE = 1;
    private static String[] PERMISSIONS_STORAGE = {
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
    };

    public static void verifyStoragePermissions(Activity activity) {
        // Check if we have write permission
        int permission = ActivityCompat.checkSelfPermission(activity, Manifest.permission.WRITE_EXTERNAL_STORAGE);

        if (permission != PackageManager.PERMISSION_GRANTED) {
            // We don't have permission so prompt the user
            ActivityCompat.requestPermissions(
                    activity,
                    PERMISSIONS_STORAGE,
                    REQUEST_EXTERNAL_STORAGE
            );
        }
    }
}