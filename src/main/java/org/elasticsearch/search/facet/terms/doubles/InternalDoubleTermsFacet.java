/*
 * Licensed to Elastic Search and Shay Banon under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. Elastic Search licenses this
 * file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.search.facet.terms.doubles;

import com.google.common.collect.ImmutableList;
import gnu.trove.iterator.TDoubleIntIterator;
import gnu.trove.map.hash.TDoubleIntHashMap;
import org.elasticsearch.common.CacheRecycler;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.bytes.HashedBytesArray;
import org.elasticsearch.common.collect.BoundedTreeSet;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.text.StringText;
import org.elasticsearch.common.text.Text;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentBuilderString;
import org.elasticsearch.search.facet.Facet;
import org.elasticsearch.search.facet.terms.InternalTermsFacet;
import org.elasticsearch.search.facet.terms.TermsFacet;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;

/**
 *
 */
public class InternalDoubleTermsFacet extends InternalTermsFacet {

    private static final BytesReference STREAM_TYPE = new HashedBytesArray(Strings.toUTF8Bytes("dTerms"));

    public static void registerStream() {
        Streams.registerStream(STREAM, STREAM_TYPE);
    }

    static Stream STREAM = new Stream() {
        @Override
        public Facet readFacet(StreamInput in) throws IOException {
            return readTermsFacet(in);
        }
    };

    @Override
    public BytesReference streamType() {
        return STREAM_TYPE;
    }

    public static class DoubleEntry implements Entry {

        double term;
        int count;

        public DoubleEntry(double term, int count) {
            this.term = term;
            this.count = count;
        }

        public Text getTerm() {
            return new StringText(Double.toString(term));
        }

        @Override
        public Number getTermAsNumber() {
            return term;
        }

        @Override
        public int getCount() {
            return count;
        }

        @Override
        public int compareTo(Entry o) {
            double anotherVal = ((DoubleEntry) o).term;
            if (term < anotherVal) {
                return -1;
            }
            if (term == anotherVal) {
                int i = count - o.getCount();
                if (i == 0) {
                    i = System.identityHashCode(this) - System.identityHashCode(o);
                }
                return i;
            }
            return 1;
        }
    }

    int requiredSize;
    long missing;
    long total;
    Collection<DoubleEntry> entries = ImmutableList.of();
    ComparatorType comparatorType;

    InternalDoubleTermsFacet() {
    }

    public InternalDoubleTermsFacet(String name, ComparatorType comparatorType, int requiredSize, Collection<DoubleEntry> entries, long missing, long total) {
        super(name);
        this.comparatorType = comparatorType;
        this.requiredSize = requiredSize;
        this.entries = entries;
        this.missing = missing;
        this.total = total;
    }

    @Override
    public List<DoubleEntry> getEntries() {
        if (!(entries instanceof List)) {
            entries = ImmutableList.copyOf(entries);
        }
        return (List<DoubleEntry>) entries;
    }

    @SuppressWarnings({"unchecked"})
    @Override
    public Iterator<Entry> iterator() {
        return (Iterator) entries.iterator();
    }

    @Override
    public long getMissingCount() {
        return this.missing;
    }

    @Override
    public long getTotalCount() {
        return this.total;
    }

    @Override
    public long getOtherCount() {
        long other = total;
        for (Entry entry : entries) {
            other -= entry.getCount();
        }
        return other;
    }

    @Override
    public Facet reduce(List<Facet> facets) {
        if (facets.size() == 1) {
            return facets.get(0);
        }

        InternalDoubleTermsFacet first = null;

        TDoubleIntHashMap aggregated = CacheRecycler.popDoubleIntMap();
        long missing = 0;
        long total = 0;
        for (Facet facet : facets) {
            TermsFacet termsFacet = (TermsFacet) facet;
            // termsFacet could be of type InternalStringTermsFacet representing unmapped fields
            if (first == null && termsFacet instanceof InternalDoubleTermsFacet) {
                first = (InternalDoubleTermsFacet) termsFacet;
            }
            missing += termsFacet.getMissingCount();
            total += termsFacet.getTotalCount();
            for (Entry entry : termsFacet.getEntries()) {
                aggregated.adjustOrPutValue(((DoubleEntry)entry).term, entry.getCount(), entry.getCount());
            }
        }

        BoundedTreeSet<DoubleEntry> ordered = new BoundedTreeSet<DoubleEntry>(first.comparatorType.comparator(), first.requiredSize);
        for (TDoubleIntIterator it = aggregated.iterator(); it.hasNext(); ) {
            it.advance();
            ordered.add(new DoubleEntry(it.key(), it.value()));
        }
        first.entries = ordered;
        first.missing = missing;
        first.total = total;

        CacheRecycler.pushDoubleIntMap(aggregated);

        return first;
    }

    static final class Fields {
        static final XContentBuilderString _TYPE = new XContentBuilderString("_type");
        static final XContentBuilderString MISSING = new XContentBuilderString("missing");
        static final XContentBuilderString TOTAL = new XContentBuilderString("total");
        static final XContentBuilderString OTHER = new XContentBuilderString("other");
        static final XContentBuilderString TERMS = new XContentBuilderString("terms");
        static final XContentBuilderString TERM = new XContentBuilderString("term");
        static final XContentBuilderString COUNT = new XContentBuilderString("count");
    }

    @Override
    public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
        builder.startObject(getName());
        builder.field(Fields._TYPE, TermsFacet.TYPE);
        builder.field(Fields.MISSING, missing);
        builder.field(Fields.TOTAL, total);
        builder.field(Fields.OTHER, getOtherCount());
        builder.startArray(Fields.TERMS);
        for (DoubleEntry entry : entries) {
            builder.startObject();
            builder.field(Fields.TERM, entry.term);
            builder.field(Fields.COUNT, entry.getCount());
            builder.endObject();
        }
        builder.endArray();
        builder.endObject();
        return builder;
    }

    public static InternalDoubleTermsFacet readTermsFacet(StreamInput in) throws IOException {
        InternalDoubleTermsFacet facet = new InternalDoubleTermsFacet();
        facet.readFrom(in);
        return facet;
    }

    @Override
    public void readFrom(StreamInput in) throws IOException {
        super.readFrom(in);
        comparatorType = ComparatorType.fromId(in.readByte());
        requiredSize = in.readVInt();
        missing = in.readVLong();
        total = in.readVLong();

        int size = in.readVInt();
        entries = new ArrayList<DoubleEntry>(size);
        for (int i = 0; i < size; i++) {
            entries.add(new DoubleEntry(in.readDouble(), in.readVInt()));
        }
    }

    @Override
    public void writeTo(StreamOutput out) throws IOException {
        super.writeTo(out);
        out.writeByte(comparatorType.id());
        out.writeVInt(requiredSize);
        out.writeVLong(missing);
        out.writeVLong(total);

        out.writeVInt(entries.size());
        for (DoubleEntry entry : entries) {
            out.writeDouble(entry.term);
            out.writeVInt(entry.getCount());
        }
    }
}