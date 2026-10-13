package org.json;
import java.util.ArrayList;
import java.util.Collection;
public final class JSONArray {
    private final ArrayList<Object> values = new ArrayList<>();
    public JSONArray() {}
    public JSONArray(Collection<?> c) { values.addAll(c); }
    public JSONArray put(Object value) { values.add(value); return this; }
    public int length() { return values.size(); }
}
