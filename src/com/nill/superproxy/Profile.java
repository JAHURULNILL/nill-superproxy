package com.nill.superproxy;

import org.json.JSONException;
import org.json.JSONObject;
import java.util.UUID;

public class Profile {
    public String id;
    public String name = "";
    public String protocol = "SOCKS5"; // SOCKS5 | HTTP
    public String host = "";
    public int port = 1080;
    public boolean auth = false;
    public String username = "";
    public String password = "";
    public boolean tls = false;
    public String dnsServer = "1.1.1.1";
    public boolean favorite = false;

    public Profile() { id = UUID.randomUUID().toString(); }

    public String endpoint() { return host + ":" + port; }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", id); o.put("name", name); o.put("protocol", protocol);
        o.put("host", host); o.put("port", port); o.put("auth", auth);
        o.put("username", username); o.put("password", password);
        o.put("tls", tls); o.put("dnsServer", dnsServer); o.put("favorite", favorite);
        return o;
    }

    public static Profile fromJson(JSONObject o) {
        Profile p = new Profile();
        p.id = o.optString("id", p.id);
        p.name = o.optString("name", "");
        p.protocol = o.optString("protocol", "SOCKS5");
        p.host = o.optString("host", "");
        p.port = o.optInt("port", 1080);
        p.auth = o.optBoolean("auth", false);
        p.username = o.optString("username", "");
        p.password = o.optString("password", "");
        p.tls = o.optBoolean("tls", false);
        p.dnsServer = o.optString("dnsServer", "1.1.1.1");
        p.favorite = o.optBoolean("favorite", false);
        return p;
    }

    public Profile copy() {
        Profile p = new Profile();
        p.name = name; p.protocol = protocol; p.host = host; p.port = port;
        p.auth = auth; p.username = username; p.password = password;
        p.tls = tls; p.dnsServer = dnsServer; p.favorite = favorite;
        return p;
    }
}
