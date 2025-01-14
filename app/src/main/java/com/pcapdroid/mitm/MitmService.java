/*
 * This file is part of PCAPdroid.
 *
 * PCAPdroid is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * PCAPdroid is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with PCAPdroid.  If not, see <http://www.gnu.org/licenses/>.
 *
 * Copyright 2022 - Emanuele Faranda
 */

package com.pcapdroid.mitm;

import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.util.Log;

import com.chaquo.python.PyObject;
import com.chaquo.python.Python;

import java.io.IOException;
import java.lang.ref.WeakReference;

import com.pcapdroid.mitm.MitmAPI.MitmConfig;

public class MitmService extends Service implements Runnable {
    static final String TAG = "Mitmproxy";
    Messenger mMessenger;
    ParcelFileDescriptor mFd;
    Thread mThread;
    PyObject mitm;
    MitmConfig mConf;

    @Override
    public void onCreate() {
        Python py = Python.getInstance();
        mitm = py.getModule("mitm");

        super.onCreate();
    }

    @Override
    public void onDestroy() {
        _stop();
        super.onDestroy();
    }

    static class IncomingHandler extends Handler {
        final WeakReference<MitmService> mReference;

        public IncomingHandler(Looper looper, MitmService service) {
            super(looper);
            mReference = new WeakReference<>(service);
        }

        @Override
        public void handleMessage(Message msg) {
            MitmService instance = mReference.get();
            if(instance != null)
                instance.handleMessage(msg);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        mMessenger = new Messenger(new IncomingHandler(getMainLooper(),this));
        return mMessenger.getBinder();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_NOT_STICKY;
    }

    private void handleMessage(Message msg) {
        switch (msg.what) {
            case MitmAPI.MSG_START_MITM:
                mFd = (ParcelFileDescriptor) msg.obj;
                mConf = (MitmConfig) msg.getData().getSerializable(MitmAPI.MITM_CONFIG);

                if(mThread == null) {
                    mThread = new Thread(MitmService.this);
                    mThread.start();
                } else
                    Log.w(TAG, "Thread already active");
                break;
            case MitmAPI.MSG_STOP_MITM:
                //Log.d(TAG, "stop called");
                _stop();
                break;
            case MitmAPI.MSG_GET_CA_CERTIFICATE:
                if(mThread == null)
                    handleGetCaCertificate(msg.replyTo);
                else {
                    Log.w(TAG, "Not supported while mitm running");
                    replyWithError(msg.replyTo);
                }
                break;
            default:
                Log.w(TAG, "Unknown message: " + msg.what);
        }
    }

    private String getMitmproxyArgs() {
        StringBuilder builder = new StringBuilder();

        builder.append("-q --set onboarding=false --mode socks5 --listen-host 127.0.0.1 -p ");
        builder.append(mConf.proxyPort);

        if(mConf.proxyAuth != null) {
            builder.append(" --proxyauth ");
            builder.append(mConf.proxyAuth);
        }

        if(mConf.sslInsecure)
            builder.append(" --ssl-insecure");

        return builder.toString();
    }

    @Override
    public void run() {
        String args = getMitmproxyArgs();
        Log.d(TAG, "mitmdump " + args);

        try {
            mitm.callAttr("run", mFd.getFd(), mConf.dumpMasterSecrets, args);
        } finally {
            try {
                if(mFd != null)
                    mFd.close();
            } catch (IOException e) {
                e.printStackTrace();
            }

            Log.d(TAG, "Done");
            mFd = null;
            mConf = null;
            mThread = null;
        }
    }

    private void _stop() {
        mitm.callAttr("stop");

        while((mThread != null) && (mThread.isAlive())) {
            try {
                mThread.join();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        stopSelf();
    }

    private void handleGetCaCertificate(Messenger replyTo) {
        String cert = null;

        PyObject pyres = mitm.callAttr("getCAcert");
        if(pyres != null)
            cert = pyres.toJava(String.class);

        if(replyTo != null) {
            Bundle bundle = new Bundle();
            bundle.putString(MitmAPI.CERTIFICATE_RESULT, cert);
            Message msg = Message.obtain(null, MitmAPI.MSG_GET_CA_CERTIFICATE);
            msg.setData(bundle);

            try {
                replyTo.send(msg);
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }
    }

    private void replyWithError(Messenger replyTo) {
        if(replyTo == null)
            return;

        Message msg = Message.obtain(null, MitmAPI.MSG_ERROR);
        try {
            replyTo.send(msg);
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }
}
