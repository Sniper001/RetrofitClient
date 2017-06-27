package com.jady.retrofitclient.download;

import com.jady.retrofitclient.request.CommonRequest;
import com.jady.retrofitclient.subscriber.DownloadSubscriber;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.ResponseBody;
import retrofit2.Retrofit;
import retrofit2.adapter.rxjava.RxJavaCallAdapterFactory;
import retrofit2.converter.gson.GsonConverterFactory;
import rx.android.schedulers.AndroidSchedulers;
import rx.functions.Func1;
import rx.schedulers.Schedulers;

/**
 * Created by jady on 2017/2/6.
 */
public class DownloadManager {
    private Set<DownloadInfo> downloadInfos;
    private HashMap<String, DownloadSubscriber> subscriberMap;

    private static class DownloadManagerHolder {
        private static final DownloadManager downloadManager = new DownloadManager();
    }

    public static DownloadManager getInstance() {
        return DownloadManagerHolder.downloadManager;
    }

    public DownloadManager() {
        downloadInfos = new HashSet<>();
        subscriberMap = new HashMap<>();
    }

    public void startDown(final DownloadInfo info) {
        if (info == null || subscriberMap.get(info.getUrl()) != null) {
            subscriberMap.get(info.getUrl()).setDownloadInfo(info);
            return;
        }
        DownloadSubscriber subscriber = new DownloadSubscriber(info);
        subscriberMap.put(info.getUrl(), subscriber);
        CommonRequest commonRequest;
        if (downloadInfos.contains(info)) {
            commonRequest = info.getRequest();
        } else {
            DownloadInterceptor interceptor = DownloadInterceptor.create(info, subscriber);
            OkHttpClient.Builder builder = new OkHttpClient.Builder();
            builder.connectTimeout(15, TimeUnit.SECONDS);
            builder.addInterceptor(interceptor);
            Retrofit.Builder retrofitBuilder = new Retrofit.Builder()
                    .client(builder.build())
                    .addConverterFactory(GsonConverterFactory.create())
                    .addCallAdapterFactory(RxJavaCallAdapterFactory.create());
            commonRequest = retrofitBuilder.build().create(CommonRequest.class);
            info.setRequest(commonRequest);
            downloadInfos.add(info);
        }
        commonRequest.download(info.getUrl())
                .subscribeOn(Schedulers.io())
                .unsubscribeOn(Schedulers.io())
                .map(new Func1<ResponseBody, DownloadInfo>() {
                    @Override
                    public DownloadInfo call(ResponseBody responseBody) {
                        try {
                            writeCache(responseBody, new File(info.getSavePath()), info);
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                        return info;
                    }
                })
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(subscriber);
    }

    public void startAll() {
        for (DownloadInfo info : downloadInfos) {
            startDown(info);
        }
    }

    public void stopDown(DownloadInfo info) {
        if (info == null) return;
        info.setState(DownloadInfo.STOP);
        info.getListener().onStop();
        if (subscriberMap.containsKey(info.getUrl())) {
            DownloadSubscriber subscriber = subscriberMap.get(info.getUrl());
            subscriber.unsubscribe();
            subscriberMap.remove(info.getUrl());
        }
    }

    public void pause(DownloadInfo info) {
        if (info == null) return;
        info.setState(DownloadInfo.PAUSE);
        info.getListener().onPause();
        if (subscriberMap.containsKey(info.getUrl())) {
            DownloadSubscriber subscriber = subscriberMap.get(info.getUrl());
            subscriber.unsubscribe();
            subscriberMap.remove(info.getUrl());
        }
    }

    public void stopAll() {
        for (DownloadInfo info : downloadInfos) {
            stopDown(info);
        }
        subscriberMap.clear();
        downloadInfos.clear();
    }

    public void pauseAll() {
        for (DownloadInfo info : downloadInfos) {
            pause(info);
        }
        subscriberMap.clear();
        downloadInfos.clear();
    }

    public Set<DownloadInfo> getDownloadInfos() {
        return downloadInfos;
    }

    public void remove(DownloadInfo info) {
        subscriberMap.remove(info.getUrl());
        downloadInfos.remove(info);
    }

    /**
     * 写入文件
     *
     * @param file
     * @param info
     * @throws IOException
     */
    public static void writeCache(ResponseBody responseBody, File file, DownloadInfo info) throws IOException {
        if (!file.getParentFile().exists())
            file.getParentFile().mkdirs();
        long allLength;
        if (info.getContentLength() == 0) {
            allLength = responseBody.contentLength();
        } else {
            allLength = info.getContentLength();
        }
        FileChannel channelOut = null;
        RandomAccessFile randomAccessFile = null;
        randomAccessFile = new RandomAccessFile(file, "rwd");
        channelOut = randomAccessFile.getChannel();
        MappedByteBuffer mappedBuffer = channelOut.map(FileChannel.MapMode.READ_WRITE,
                info.getReadLength(), allLength - info.getReadLength());
        byte[] buffer = new byte[1024 * 8];
        int len;
        int record = 0;
        while ((len = responseBody.byteStream().read(buffer)) != -1) {
            mappedBuffer.put(buffer, 0, len);
            record += len;
        }
        responseBody.byteStream().close();
        if (channelOut != null) {
            channelOut.close();
        }
        if (randomAccessFile != null) {
            randomAccessFile.close();
        }
    }

}
