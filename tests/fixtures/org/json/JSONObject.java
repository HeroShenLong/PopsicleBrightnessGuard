package org.json;
import java.util.LinkedHashMap;
public final class JSONObject {
    public static final Object NULL = new Object();
    private final LinkedHashMap<String,Object> values = new LinkedHashMap<>();
    public JSONObject put(String key, Object value) { values.put(key,value); return this; }
    public Object remove(String key) { return values.remove(key); }
    public Object opt(String key) { return values.get(key); }
    public boolean has(String key) { return values.containsKey(key); }
    public boolean optBoolean(String key) { return Boolean.TRUE.equals(values.get(key)); }
    public double optDouble(String key,double fallback) { Object v=values.get(key); return v instanceof Number?((Number)v).doubleValue():fallback; }
    public long optLong(String key) { Object v=values.get(key); return v instanceof Number?((Number)v).longValue():0; }
    public String optString(String key) { Object v=values.get(key); return v==null?"":v.toString(); }
    public String getString(String key) { return optString(key); }
    public JSONArray optJSONArray(String key) { Object v=values.get(key); return v instanceof JSONArray?(JSONArray)v:null; }
    public JSONObject optJSONObject(String key) { Object v=values.get(key); return v instanceof JSONObject?(JSONObject)v:null; }
    public String toString() { return values.toString(); }
}
