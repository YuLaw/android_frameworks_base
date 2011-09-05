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

package android.net.wifi.p2p;

import android.os.Parcelable;
import android.os.Parcel;
import android.util.Log;

import java.util.regex.Pattern;

/**
 * A class representing a Wi-Fi p2p device
 * @hide
 */
public class WifiP2pDevice implements Parcelable {

    private static final String TAG = "WifiP2pDevice";
    /**
     * Device name
     */
    public String deviceName;

    /**
     * Device MAC address
     */
    public String deviceAddress;

    /**
     * interfaceAddress
     *
     * This address is used during group owner negotiation as the Intended
     * P2P Interface Address and the group interface will be created with
     * address as the local address in case of successfully completed
     * negotiation.
     */
    public String interfaceAddress;

    /**
     * Primary device type
     */
    public String primaryDeviceType;

    /**
     * Secondary device type
     */
    public String secondaryDeviceType;


    // These definitions match the ones in wpa_supplicant
    /* WPS config methods supported */
    private static final int WPS_CONFIG_USBA            = 0x0001;
    private static final int WPS_CONFIG_ETHERNET        = 0x0002;
    private static final int WPS_CONFIG_LABEL           = 0x0004;
    private static final int WPS_CONFIG_DISPLAY         = 0x0008;
    private static final int WPS_CONFIG_EXT_NFC_TOKEN   = 0x0010;
    private static final int WPS_CONFIG_INT_NFC_TOKEN   = 0x0020;
    private static final int WPS_CONFIG_NFC_INTERFACE   = 0x0040;
    private static final int WPS_CONFIG_PUSHBUTTON      = 0x0080;
    private static final int WPS_CONFIG_KEYPAD          = 0x0100;
    private static final int WPS_CONFIG_VIRT_PUSHBUTTON = 0x0280;
    private static final int WPS_CONFIG_PHY_PUSHBUTTON  = 0x0480;
    private static final int WPS_CONFIG_VIRT_DISPLAY    = 0x2008;
    private static final int WPS_CONFIG_PHY_DISPLAY     = 0x4008;

    /* Device Capability bitmap */
    private static final int DEVICE_CAPAB_SERVICE_DISCOVERY         = 1;
    private static final int DEVICE_CAPAB_CLIENT_DISCOVERABILITY    = 1<<1;
    private static final int DEVICE_CAPAB_CONCURRENT_OPER           = 1<<2;
    private static final int DEVICE_CAPAB_INFRA_MANAGED             = 1<<3;
    private static final int DEVICE_CAPAB_DEVICE_LIMIT              = 1<<4;
    private static final int DEVICE_CAPAB_INVITATION_PROCEDURE      = 1<<5;

    /* Group Capability bitmap */
    private static final int GROUP_CAPAB_GROUP_OWNER                = 1;
    private static final int GROUP_CAPAB_PERSISTENT_GROUP           = 1<<1;
    private static final int GROUP_CAPAB_GROUP_LIMIT                = 1<<2;
    private static final int GROUP_CAPAB_INTRA_BSS_DIST             = 1<<3;
    private static final int GROUP_CAPAB_CROSS_CONN                 = 1<<4;
    private static final int GROUP_CAPAB_PERSISTENT_RECONN          = 1<<5;
    private static final int GROUP_CAPAB_GROUP_FORMATION            = 1<<6;

    /**
     * WPS config methods supported
     */
    public int wpsConfigMethodsSupported;

    /**
     * Device capability
     */
    public int deviceCapability;

    /**
     * Group capability
     */
    public int groupCapability;

    public enum Status {
        CONNECTED,
        INVITED,
        FAILED,
        AVAILABLE,
        UNAVAILABLE,
    }

    public Status status = Status.UNAVAILABLE;

    public WifiP2pDevice() {
    }

    /**
     * @param string formats supported include
     *  P2P-DEVICE-FOUND fa:7b:7a:42:02:13 p2p_dev_addr=fa:7b:7a:42:02:13
     *  pri_dev_type=1-0050F204-1 name='p2p-TEST1' config_methods=0x188 dev_capab=0x27
     *  group_capab=0x0
     *
     *  P2P-DEVICE-LOST p2p_dev_addr=fa:7b:7a:42:02:13
     *
     *  fa:7b:7a:42:02:13
     *
     *  P2P-PROV-DISC-PBC-REQ 42:fc:89:e1:e2:27 p2p_dev_addr=42:fc:89:e1:e2:27
     *  pri_dev_type=1-0050F204-1 name='p2p-TEST2' config_methods=0x188 dev_capab=0x27
     *  group_capab=0x0
     *
     *  P2P-PROV-DISC-ENTER-PIN 42:fc:89:e1:e2:27 p2p_dev_addr=42:fc:89:e1:e2:27
     *  pri_dev_type=1-0050F204-1 name='p2p-TEST2' config_methods=0x188 dev_capab=0x27
     *  group_capab=0x0
     *
     *  P2P-PROV-DISC-SHOW-PIN 42:fc:89:e1:e2:27 44490607 p2p_dev_addr=42:fc:89:e1:e2:27
     *  pri_dev_type=1-0050F204-1 name='p2p-TEST2' config_methods=0x188 dev_capab=0x27
     *  group_capab=0x0
     *
     *  Note: The events formats can be looked up in the wpa_supplicant code
     */
    public WifiP2pDevice(String string) throws IllegalArgumentException {
        String[] tokens = string.split(" ");

        if (tokens.length < 1) {
            throw new IllegalArgumentException("Malformed supplicant event");
        }

        /* Just a device address */
        if (tokens.length == 1) {
            deviceAddress = string;
            return;
        }

        for (String token : tokens) {
            String[] nameValue = token.split("=");
            if (nameValue.length != 2) continue;

            if (nameValue[0].equals("p2p_dev_addr")) {
                deviceAddress = nameValue[1];
                continue;
            }

            if (nameValue[0].equals("pri_dev_type")) {
                primaryDeviceType = nameValue[1];
                continue;
            }

            if (nameValue[0].equals("name")) {
                deviceName = trimQuotes(nameValue[1]);
                continue;
            }

            if (nameValue[0].equals("config_methods")) {
                wpsConfigMethodsSupported = parseHex(nameValue[1]);
                continue;
            }

            if (nameValue[0].equals("dev_capab")) {
                deviceCapability = parseHex(nameValue[1]);
                continue;
            }

            if (nameValue[0].equals("group_capab")) {
                groupCapability = parseHex(nameValue[1]);
                continue;
            }
        }

        if (tokens[0].startsWith("P2P-DEVICE-FOUND")) {
            status = Status.AVAILABLE;
        }
    }

    public boolean isGroupOwner() {
        return (groupCapability & GROUP_CAPAB_GROUP_OWNER) != 0;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof WifiP2pDevice)) return false;

        WifiP2pDevice other = (WifiP2pDevice) obj;
        if (other == null || other.deviceAddress == null) {
            return (deviceAddress == null);
        }
        return other.deviceAddress.equals(deviceAddress);
    }

    public String toString() {
        StringBuffer sbuf = new StringBuffer();
        sbuf.append("Device: ").append(deviceName);
        sbuf.append("\n deviceAddress: ").append(deviceAddress);
        sbuf.append("\n interfaceAddress: ").append(interfaceAddress);
        sbuf.append("\n primary type: ").append(primaryDeviceType);
        sbuf.append("\n secondary type: ").append(secondaryDeviceType);
        sbuf.append("\n wps: ").append(wpsConfigMethodsSupported);
        sbuf.append("\n grpcapab: ").append(groupCapability);
        sbuf.append("\n devcapab: ").append(deviceCapability);
        sbuf.append("\n status: ").append(status);
        return sbuf.toString();
    }

    /** Implement the Parcelable interface {@hide} */
    public int describeContents() {
        return 0;
    }

    /** copy constructor {@hide} */
    public WifiP2pDevice(WifiP2pDevice source) {
        if (source != null) {
            deviceName = source.deviceName;
            deviceAddress = source.deviceAddress;
            interfaceAddress = source.interfaceAddress;
            primaryDeviceType = source.primaryDeviceType;
            secondaryDeviceType = source.secondaryDeviceType;
            wpsConfigMethodsSupported = source.wpsConfigMethodsSupported;
            deviceCapability = source.deviceCapability;
            groupCapability = source.groupCapability;
            status = source.status;
        }
    }

    /** Implement the Parcelable interface {@hide} */
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(deviceName);
        dest.writeString(deviceAddress);
        dest.writeString(interfaceAddress);
        dest.writeString(primaryDeviceType);
        dest.writeString(secondaryDeviceType);
        dest.writeInt(wpsConfigMethodsSupported);
        dest.writeInt(deviceCapability);
        dest.writeInt(groupCapability);
        dest.writeString(status.name());
    }

    /** Implement the Parcelable interface {@hide} */
    public static final Creator<WifiP2pDevice> CREATOR =
        new Creator<WifiP2pDevice>() {
            public WifiP2pDevice createFromParcel(Parcel in) {
                WifiP2pDevice device = new WifiP2pDevice();
                device.deviceName = in.readString();
                device.deviceAddress = in.readString();
                device.interfaceAddress = in.readString();
                device.primaryDeviceType = in.readString();
                device.secondaryDeviceType = in.readString();
                device.wpsConfigMethodsSupported = in.readInt();
                device.deviceCapability = in.readInt();
                device.groupCapability = in.readInt();
                device.status = Status.valueOf(in.readString());
                return device;
            }

            public WifiP2pDevice[] newArray(int size) {
                return new WifiP2pDevice[size];
            }
        };

    private String trimQuotes(String str) {
        str = str.trim();
        if (str.startsWith("'") && str.endsWith("'")) {
            return str.substring(1, str.length()-1);
        }
        return str;
    }

    //supported formats: 0x1abc, 0X1abc, 1abc
    private int parseHex(String hexString) {
        int num = 0;
        if (hexString.startsWith("0x") || hexString.startsWith("0X")) {
            hexString = hexString.substring(2);
        }

        try {
            num = Integer.parseInt(hexString, 16);
        } catch(NumberFormatException e) {
            Log.e(TAG, "Failed to parse hex string " + hexString);
        }
        return num;
    }
}