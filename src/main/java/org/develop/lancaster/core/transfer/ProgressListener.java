package org.develop.lancaster.core.transfer;

public interface ProgressListener {
    void onProgress(long currentBytes, long totalBytes);
}