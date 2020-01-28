package com.wolt.osm.parallelpbf.encoder;

import com.google.protobuf.ByteString;
import crosby.binary.Osmformat;

import java.util.HashMap;
import java.util.Map;

/**
 * Entity specific extension of OsmEncoder.
 */
public abstract class OsmEntityEncoder extends OsmEncoder {
    /**
     * Keeps current maximum string index value.
     */
    private Integer stringIndex = 0;

    /**
     * Size of strings kept in the string table.
     */
    private Integer stringTableSize = 0;

    /**
     * Reverse index mapping - for string already stored in the table it will map
     * string values back to their indices.
     */
    private Map<String, Integer> indexMap = new HashMap<>();

    /**
     * The string table.
     */
    private Osmformat.StringTable.Builder strings = Osmformat.StringTable.newBuilder();

    /**
     * Adds string to the string table and adds string size to the stringtable size.
     * @param str String to add.
     * @return String index in table.
     */
    protected int addStringToTable(final String str) {
        stringTableSize = stringTableSize + str.length();
        strings.addS(ByteString.copyFromUtf8(str));
        return ++stringIndex;
    }

    /**
     * Finds stringtable index for a supplied string. Will return either existing index for a string
     * or add string to the stringtable and emit a new index.
     * @param s String to index.
     * @return Strings index in the stringtable.
     */
    protected int getStringIndex(final String s) {
        return indexMap.computeIfAbsent(s, this::addStringToTable);
    }

    /**
     * Returns current estimated size of the string table.
     * @return size in bytes.
     */
    protected int getStringSize() {
        return stringTableSize;
    }

    /**
     * String table accessor.
     * @return string table value.
     */
    protected Osmformat.StringTable.Builder getStrings() {
        return strings;
    }

    /**
     * Default constructor.
     */
    public OsmEntityEncoder() {
        strings.addS(ByteString.EMPTY); //First entry with index 0 is always empty.
    }

    /**
     * Provides approximate size of the future blob.
     * @return Estimated approximate maximum size of a blob.
     */
    public abstract int estimateSize();

    /**
     * Build a blob from the collected data. Encoder will become
     * unusable after that call.
     * @return OSM PBF primitiveBlock blob.
     */
    public abstract byte[] write();

}
