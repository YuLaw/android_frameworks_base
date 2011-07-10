/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.connectivity;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.net.INetworkManagementEventObserver;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Binder;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.util.Log;

import com.android.internal.R;
import com.android.internal.net.LegacyVpnInfo;
import com.android.internal.net.VpnConfig;
import com.android.server.ConnectivityService.VpnCallback;

import java.io.OutputStream;
import java.nio.charset.Charsets;
import java.util.Arrays;

/**
 * @hide
 */
public class Vpn extends INetworkManagementEventObserver.Stub {

    private final static String TAG = "Vpn";
    private final static String VPN = android.Manifest.permission.VPN;

    private final Context mContext;
    private final VpnCallback mCallback;

    private String mPackage = VpnConfig.LEGACY_VPN;
    private String mInterface;
    private LegacyVpnRunner mLegacyVpnRunner;

    public Vpn(Context context, VpnCallback callback) {
        mContext = context;
        mCallback = callback;
    }

    /**
     * Protect a socket from routing changes by binding it to the given
     * interface. The socket IS closed by this method.
     *
     * @param socket The socket to be bound.
     * @param name The name of the interface.
     */
    public void protect(ParcelFileDescriptor socket, String interfaze) {
        try {
            mContext.enforceCallingPermission(VPN, "protect");
            jniProtect(socket.getFd(), interfaze);
        } finally {
            try {
                socket.close();
            } catch (Exception e) {
                // ignore
            }
        }
    }

    /**
     * Prepare for a VPN application. This method is designed to solve
     * race conditions. It first compares the current prepared package
     * with {@code oldPackage}. If they are the same, the prepared
     * package is revoked and replaced with {@code newPackage}. If
     * {@code oldPackage} is {@code null}, the comparison is omitted.
     * If {@code newPackage} is the same package or {@code null}, the
     * revocation is omitted. This method returns {@code true} if the
     * operation is succeeded.
     *
     * Legacy VPN is handled specially since it is not a real package.
     * It uses {@link VpnConfig#LEGACY_VPN} as its package name, and
     * it can be revoked by itself.
     *
     * @param oldPackage The package name of the old VPN application.
     * @param newPackage The package name of the new VPN application.
     * @return true if the operation is succeeded.
     */
    public synchronized boolean prepare(String oldPackage, String newPackage) {
        // Return false if the package does not match.
        if (oldPackage != null && !oldPackage.equals(mPackage)) {
            return false;
        }

        // Return true if we do not need to revoke.
        if (newPackage == null ||
                (newPackage.equals(mPackage) && !newPackage.equals(VpnConfig.LEGACY_VPN))) {
            return true;
        }

        // Only system user can revoke a package.
        if (Binder.getCallingUid() != Process.SYSTEM_UID) {
            throw new SecurityException("Unauthorized Caller");
        }

        // Check the permission of the given package.
        PackageManager pm = mContext.getPackageManager();
        if (!newPackage.equals(VpnConfig.LEGACY_VPN) &&
                pm.checkPermission(VPN, newPackage) != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException(newPackage + " does not have " + VPN);
        }

        // Reset the interface and hide the notification.
        if (mInterface != null) {
            jniReset(mInterface);
            mCallback.restore();
            hideNotification();
            mInterface = null;
        }

        // Send out the broadcast or stop LegacyVpnRunner.
        if (!mPackage.equals(VpnConfig.LEGACY_VPN)) {
            Intent intent = new Intent(VpnConfig.ACTION_VPN_REVOKED);
            intent.setPackage(mPackage);
            intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
            mContext.sendBroadcast(intent);
        } else if (mLegacyVpnRunner != null) {
            mLegacyVpnRunner.exit();
            mLegacyVpnRunner = null;
        }

        Log.i(TAG, "Switched from " + mPackage + " to " + newPackage);
        mPackage = newPackage;
        return true;
    }

    /**
     * Establish a VPN network and return the file descriptor of the VPN
     * interface. This methods returns {@code null} if the application is
     * revoked or not prepared.
     *
     * @param config The parameters to configure the network.
     * @return The file descriptor of the VPN interface.
     */
    public synchronized ParcelFileDescriptor establish(VpnConfig config) {
        // Check the permission of the caller.
        mContext.enforceCallingPermission(VPN, "establish");

        // Check if the caller is already prepared.
        PackageManager pm = mContext.getPackageManager();
        ApplicationInfo app = null;
        try {
            app = pm.getApplicationInfo(mPackage, 0);
        } catch (Exception e) {
            return null;
        }
        if (Binder.getCallingUid() != app.uid) {
            return null;
        }

        // Load the label.
        String label = app.loadLabel(pm).toString();

        // Load the icon and convert it into a bitmap.
        Drawable icon = app.loadIcon(pm);
        Bitmap bitmap = null;
        if (icon.getIntrinsicWidth() > 0 && icon.getIntrinsicHeight() > 0) {
            int width = mContext.getResources().getDimensionPixelSize(
                    android.R.dimen.notification_large_icon_width);
            int height = mContext.getResources().getDimensionPixelSize(
                    android.R.dimen.notification_large_icon_height);
            icon.setBounds(0, 0, width, height);
            bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            icon.draw(new Canvas(bitmap));
        }

        // Configure the interface. Abort if any of these steps fails.
        ParcelFileDescriptor tun = ParcelFileDescriptor.adoptFd(
                jniConfigure(config.mtu, config.addresses, config.routes));
        try {
            String interfaze = jniGetName(tun.getFd());
            if (mInterface != null && !mInterface.equals(interfaze)) {
                jniReset(mInterface);
            }
            mInterface = interfaze;
        } catch (RuntimeException e) {
            try {
                tun.close();
            } catch (Exception ex) {
                // ignore
            }
            throw e;
        }

        // Override DNS servers and search domains.
        mCallback.override(config.dnsServers, config.searchDomains);

        // Fill more values.
        config.packagz = mPackage;
        config.interfaze = mInterface;

        // Show the notification!
        showNotification(config, label, bitmap);
        return tun;
    }

    // INetworkManagementEventObserver.Stub
    public void interfaceStatusChanged(String interfaze, boolean up) {
    }

    // INetworkManagementEventObserver.Stub
    public void interfaceLinkStateChanged(String interfaze, boolean up) {
    }

    // INetworkManagementEventObserver.Stub
    public void interfaceAdded(String interfaze) {
    }

    // INetworkManagementEventObserver.Stub
    public synchronized void interfaceRemoved(String interfaze) {
        if (interfaze.equals(mInterface) && jniCheck(interfaze) == 0) {
            mCallback.restore();
            hideNotification();
            mInterface = null;
        }
    }

    private void showNotification(VpnConfig config, String label, Bitmap icon) {
        NotificationManager nm = (NotificationManager)
                mContext.getSystemService(Context.NOTIFICATION_SERVICE);

        if (nm != null) {
            String title = (label == null) ? mContext.getString(R.string.vpn_title) :
                    mContext.getString(R.string.vpn_title_long, label);
            String text = (config.session == null) ? mContext.getString(R.string.vpn_text) :
                    mContext.getString(R.string.vpn_text_long, config.session);
            config.startTime = SystemClock.elapsedRealtime();

            long identity = Binder.clearCallingIdentity();
            Notification notification = new Notification.Builder(mContext)
                    .setSmallIcon(R.drawable.vpn_connected)
                    .setLargeIcon(icon)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setContentIntent(VpnConfig.getIntentForStatusPanel(mContext, config))
                    .setDefaults(Notification.DEFAULT_ALL)
                    .setOngoing(true)
                    .getNotification();
            nm.notify(R.drawable.vpn_connected, notification);
            Binder.restoreCallingIdentity(identity);
        }
    }

    private void hideNotification() {
        NotificationManager nm = (NotificationManager)
                mContext.getSystemService(Context.NOTIFICATION_SERVICE);

        if (nm != null) {
            long identity = Binder.clearCallingIdentity();
            nm.cancel(R.drawable.vpn_connected);
            Binder.restoreCallingIdentity(identity);
        }
    }

    private native int jniConfigure(int mtu, String addresses, String routes);
    private native String jniGetName(int tun);
    private native void jniReset(String interfaze);
    private native int jniCheck(String interfaze);
    private native void jniProtect(int socket, String interfaze);

    /**
     * Start legacy VPN. This method stops the daemons and restart them
     * if arguments are not null. Heavy things are offloaded to another
     * thread, so callers will not be blocked for a long time.
     *
     * @param config The parameters to configure the network.
     * @param raoocn The arguments to be passed to racoon.
     * @param mtpd The arguments to be passed to mtpd.
     */
    public synchronized void startLegacyVpn(VpnConfig config, String[] racoon, String[] mtpd) {
        // Prepare for the new request. This also checks the caller.
        prepare(null, VpnConfig.LEGACY_VPN);

        // Start a new LegacyVpnRunner and we are done!
        mLegacyVpnRunner = new LegacyVpnRunner(config, racoon, mtpd);
        mLegacyVpnRunner.start();
    }

    /**
     * Return the information of the current ongoing legacy VPN.
     */
    public synchronized LegacyVpnInfo getLegacyVpnInfo() {
        // Only system user can call this method.
        if (Binder.getCallingUid() != Process.SYSTEM_UID) {
            throw new SecurityException("Unauthorized Caller");
        }
        return (mLegacyVpnRunner == null) ? null : mLegacyVpnRunner.getInfo();
    }

    /**
     * Bringing up a VPN connection takes time, and that is all this thread
     * does. Here we have plenty of time. The only thing we need to take
     * care of is responding to interruptions as soon as possible. Otherwise
     * requests will be piled up. This can be done in a Handler as a state
     * machine, but it is much easier to read in the current form.
     */
    private class LegacyVpnRunner extends Thread {
        private static final String TAG = "LegacyVpnRunner";
        private static final String NONE = "--";

        private final VpnConfig mConfig;
        private final String[] mDaemons;
        private final String[][] mArguments;
        private final LegacyVpnInfo mInfo;

        private long mTimer = -1;

        public LegacyVpnRunner(VpnConfig config, String[] racoon, String[] mtpd) {
            super(TAG);
            mConfig = config;
            mDaemons = new String[] {"racoon", "mtpd"};
            mArguments = new String[][] {racoon, mtpd};
            mInfo = new LegacyVpnInfo();

            // Legacy VPN is not a real package, so we use it to carry the key.
            mInfo.key = mConfig.packagz;
            mConfig.packagz = VpnConfig.LEGACY_VPN;
        }

        public void exit() {
            // We assume that everything is reset after the daemons die.
            for (String daemon : mDaemons) {
                SystemProperties.set("ctl.stop", daemon);
            }
            interrupt();
        }

        public LegacyVpnInfo getInfo() {
            // Update the info when VPN is disconnected.
            if (mInfo.state == LegacyVpnInfo.STATE_CONNECTED && mInterface == null) {
                mInfo.state = LegacyVpnInfo.STATE_DISCONNECTED;
                mInfo.intent = null;
            }
            return mInfo;
        }

        @Override
        public void run() {
            // Wait for the previous thread since it has been interrupted.
            Log.v(TAG, "Waiting");
            synchronized (TAG) {
                Log.v(TAG, "Executing");
                execute();
            }
        }

        private void checkpoint(boolean yield) throws InterruptedException {
            long now = SystemClock.elapsedRealtime();
            if (mTimer == -1) {
                mTimer = now;
                Thread.sleep(1);
            } else if (now - mTimer <= 30000) {
                Thread.sleep(yield ? 200 : 1);
            } else {
                mInfo.state = LegacyVpnInfo.STATE_TIMEOUT;
                throw new IllegalStateException("time is up");
            }
        }

        private void execute() {
            // Catch all exceptions so we can clean up few things.
            try {
                // Initialize the timer.
                checkpoint(false);
                mInfo.state = LegacyVpnInfo.STATE_INITIALIZING;

                // First stop the daemons.
                for (String daemon : mDaemons) {
                    SystemProperties.set("ctl.stop", daemon);
                }

                // Wait for the daemons to stop.
                for (String daemon : mDaemons) {
                    String key = "init.svc." + daemon;
                    while (!"stopped".equals(SystemProperties.get(key))) {
                        checkpoint(true);
                    }
                }

                // Reset the properties.
                SystemProperties.set("vpn.dns", NONE);
                SystemProperties.set("vpn.via", NONE);
                while (!NONE.equals(SystemProperties.get("vpn.dns")) ||
                        !NONE.equals(SystemProperties.get("vpn.via"))) {
                    checkpoint(true);
                }

                // Check if we need to restart any of the daemons.
                boolean restart = false;
                for (String[] arguments : mArguments) {
                    restart = restart || (arguments != null);
                }
                if (!restart) {
                    mInfo.state = LegacyVpnInfo.STATE_DISCONNECTED;
                    return;
                }
                mInfo.state = LegacyVpnInfo.STATE_CONNECTING;

                // Start the daemon with arguments.
                for (int i = 0; i < mDaemons.length; ++i) {
                    String[] arguments = mArguments[i];
                    if (arguments == null) {
                        continue;
                    }

                    // Start the daemon.
                    String daemon = mDaemons[i];
                    SystemProperties.set("ctl.start", daemon);

                    // Wait for the daemon to start.
                    String key = "init.svc." + daemon;
                    while (!"running".equals(SystemProperties.get(key))) {
                        checkpoint(true);
                    }

                    // Create the control socket.
                    LocalSocket socket = new LocalSocket();
                    LocalSocketAddress address = new LocalSocketAddress(
                            daemon, LocalSocketAddress.Namespace.RESERVED);

                    // Wait for the socket to connect.
                    while (true) {
                        try {
                            socket.connect(address);
                            break;
                        } catch (Exception e) {
                            // ignore
                        }
                        checkpoint(true);
                    }
                    socket.setSoTimeout(500);

                    // Send over the arguments.
                    OutputStream out = socket.getOutputStream();
                    for (String argument : arguments) {
                        byte[] bytes = argument.getBytes(Charsets.UTF_8);
                        if (bytes.length >= 0xFFFF) {
                            throw new IllegalArgumentException("argument is too large");
                        }
                        out.write(bytes.length >> 8);
                        out.write(bytes.length);
                        out.write(bytes);
                        checkpoint(false);
                    }

                    // Send End-Of-Arguments.
                    out.write(0xFF);
                    out.write(0xFF);
                    out.flush();
                    socket.close();
                }

                // Now here is the beast from the old days. We check few
                // properties to figure out the current status. Ideally we
                // can read things back from the sockets and get rid of the
                // properties, but we have no time...
                while (NONE.equals(SystemProperties.get("vpn.dns")) ||
                        NONE.equals(SystemProperties.get("vpn.via"))) {

                    // Check if a running daemon is dead.
                    for (int i = 0; i < mDaemons.length; ++i) {
                        String daemon = mDaemons[i];
                        if (mArguments[i] != null && !"running".equals(
                                SystemProperties.get("init.svc." + daemon))) {
                            throw new IllegalStateException(daemon + " is dead");
                        }
                    }
                    checkpoint(true);
                }

                // Now we are connected. Get the interface.
                mConfig.interfaze = SystemProperties.get("vpn.via");

                // Get the DNS servers if they are not set in config.
                if (mConfig.dnsServers == null || mConfig.dnsServers.size() == 0) {
                    String dnsServers = SystemProperties.get("vpn.dns").trim();
                    if (!dnsServers.isEmpty()) {
                        mConfig.dnsServers = Arrays.asList(dnsServers.split(" "));
                    }
                }

                // TODO: support search domains from ISAKMP mode config.

                // The final step must be synchronized.
                synchronized (Vpn.this) {
                    // Check if the thread is interrupted while we are waiting.
                    checkpoint(false);

                    // Check if the interface is gone while we are waiting.
                    if (jniCheck(mConfig.interfaze) == 0) {
                        throw new IllegalStateException(mConfig.interfaze + " is gone");
                    }

                    // Now INetworkManagementEventObserver is watching our back.
                    mInterface = mConfig.interfaze;
                    mCallback.override(mConfig.dnsServers, mConfig.searchDomains);
                    showNotification(mConfig, null, null);

                    Log.i(TAG, "Connected!");
                    mInfo.state = LegacyVpnInfo.STATE_CONNECTED;
                    mInfo.intent = VpnConfig.getIntentForStatusPanel(mContext, null);
                }
            } catch (Exception e) {
                Log.i(TAG, "Aborting", e);
                exit();
            } finally {
                // Do not leave an unstable state.
                if (mInfo.state == LegacyVpnInfo.STATE_INITIALIZING ||
                        mInfo.state == LegacyVpnInfo.STATE_CONNECTING) {
                    mInfo.state = LegacyVpnInfo.STATE_FAILED;
                }
            }
        }
    }
}