package io.nekohasekai.sagernet.fmt.shadowsocks;

import androidx.annotation.NonNull;

import com.esotericsoftware.kryo.io.ByteBufferInput;
import com.esotericsoftware.kryo.io.ByteBufferOutput;

import org.jetbrains.annotations.NotNull;

import io.nekohasekai.sagernet.fmt.AbstractBean;
import io.nekohasekai.sagernet.fmt.KryoConverters;
import moe.matsuri.nb4a.utils.JavaUtil;

public class ShadowsocksBean extends AbstractBean {

    public String method;
    public String password;
    public String plugin;

    public Boolean sUoT;

    public Boolean enableMux;
    public Boolean muxPadding;
    public Integer muxType;
    public Integer muxConcurrency;  // max_streams
    public Integer muxMode;         // 0: max_streams, 1: connections
    public Integer muxMaxConnections;
    public Integer muxMinStreams;
    public Boolean muxBrutal;
    public Integer muxBrutalUpMbps;
    public Integer muxBrutalDownMbps;

    // Stream settings (transport and TLS). They are written to storage only
    // when in use, so profiles without them keep their exact serialized form.
    public String type;
    public String host;
    public String path;
    public String security;
    public String sni;
    public String alpn;
    public String certificates;
    public String utlsFingerprint;
    public Boolean allowInsecure;
    public String xhttpMode;
    public String xhttpExtra;

    @Override
    public void initializeDefaultValues() {
        super.initializeDefaultValues();

        if (JavaUtil.isNullOrBlank(method)) method = "aes-256-gcm";
        if (method == null) method = "";
        if (password == null) password = "";
        if (plugin == null) plugin = "";
        if (sUoT == null) sUoT = false;

        if (enableMux == null) enableMux = false;
        if (muxPadding == null) muxPadding = false;
        if (muxType == null) muxType = 0;
        if (muxConcurrency == null) muxConcurrency = 8;
        if (muxMode == null) muxMode = 0;
        if (muxMaxConnections == null) muxMaxConnections = 4;
        if (muxMinStreams == null) muxMinStreams = 4;
        if (muxBrutal == null) muxBrutal = false;
        if (muxBrutalUpMbps == null) muxBrutalUpMbps = 100;
        if (muxBrutalDownMbps == null) muxBrutalDownMbps = 100;

        if (JavaUtil.isNullOrBlank(type)) type = "tcp";
        if (host == null) host = "";
        if (path == null) path = "";
        if (JavaUtil.isNullOrBlank(security)) security = "none";
        if (sni == null) sni = "";
        if (alpn == null) alpn = "";
        if (certificates == null) certificates = "";
        if (utlsFingerprint == null) utlsFingerprint = "";
        if (allowInsecure == null) allowInsecure = false;
        if (JavaUtil.isNullOrBlank(xhttpMode)) xhttpMode = "auto";
        if (xhttpExtra == null) xhttpExtra = "";
    }

    @Override
    public void serialize(ByteBufferOutput output) {
        output.writeInt(hasStreamSettings() ? 6 : 5);
        super.serialize(output);
        output.writeString(method);
        output.writeString(password);
        output.writeString(plugin);
        output.writeBoolean(sUoT);
        // v3
        output.writeBoolean(enableMux);
        output.writeBoolean(muxPadding);
        output.writeInt(muxType);
        output.writeInt(muxConcurrency);
        // v4
        output.writeInt(muxMode);
        output.writeInt(muxMaxConnections);
        output.writeInt(muxMinStreams);
        // v5
        output.writeBoolean(muxBrutal);
        output.writeInt(muxBrutalUpMbps);
        output.writeInt(muxBrutalDownMbps);
        // v6
        if (hasStreamSettings()) {
            output.writeString(type);
            output.writeString(host);
            output.writeString(path);
            output.writeString(security);
            output.writeString(sni);
            output.writeString(alpn);
            output.writeString(certificates);
            output.writeString(utlsFingerprint);
            output.writeBoolean(allowInsecure);
            output.writeString(xhttpMode);
            output.writeString(xhttpExtra);
        }
    }

    @Override
    public void deserialize(ByteBufferInput input) {
        int version = input.readInt();
        super.deserialize(input);
        method = input.readString();
        password = input.readString();
        plugin = input.readString();
        sUoT = input.readBoolean();

        if (version >= 3) {
            enableMux = input.readBoolean();
            muxPadding = input.readBoolean();
            muxType = input.readInt();
            muxConcurrency = input.readInt();
        }
        // v4
        if (version >= 4) {
            muxMode = input.readInt();
            muxMaxConnections = input.readInt();
            muxMinStreams = input.readInt();
        }
        // v5
        if (version >= 5) {
            muxBrutal = input.readBoolean();
            muxBrutalUpMbps = input.readInt();
            muxBrutalDownMbps = input.readInt();
        }
        // v6
        if (version >= 6) {
            type = input.readString();
            host = input.readString();
            path = input.readString();
            security = input.readString();
            sni = input.readString();
            alpn = input.readString();
            certificates = input.readString();
            utlsFingerprint = input.readString();
            allowInsecure = input.readBoolean();
            xhttpMode = input.readString();
            xhttpExtra = input.readString();
        }
    }

    // True when a transport or TLS is configured.
    public boolean hasStreamSettings() {
        boolean hasTransport = type != null && !type.isEmpty() && !type.equals("tcp");
        boolean hasTls = security != null && !security.isEmpty() && !security.equals("none");
        return hasTransport || hasTls;
    }

    @NotNull
    @Override
    public String getHash() {
        return buildTypedHash("shadowsocks");
    }

    @NotNull
    @Override
    public ShadowsocksBean clone() {
        return KryoConverters.deserialize(new ShadowsocksBean(), KryoConverters.serialize(this));
    }

    public static final Creator<ShadowsocksBean> CREATOR = new CREATOR<ShadowsocksBean>() {
        @NonNull
        @Override
        public ShadowsocksBean newInstance() {
            return new ShadowsocksBean();
        }

        @Override
        public ShadowsocksBean[] newArray(int size) {
            return new ShadowsocksBean[size];
        }
    };
}
