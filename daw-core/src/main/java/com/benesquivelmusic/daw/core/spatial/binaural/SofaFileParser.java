package com.benesquivelmusic.daw.core.spatial.binaural;

import com.benesquivelmusic.daw.sdk.spatial.HrtfData;
import com.benesquivelmusic.daw.sdk.spatial.SphericalCoordinate;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure-Java parser for SOFA (Spatially Oriented Format for Acoustics) files.
 *
 * <p>SOFA files (AES69 standard) store HRTF datasets in the NetCDF-4/HDF5
 * binary format. This parser reads enough of the HDF5 structure to extract
 * the datasets required for binaural rendering:</p>
 * <ul>
 *   <li>{@code SourcePosition} — measured directions [M × 3]</li>
 *   <li>{@code Data.IR} — impulse responses [M × R × N]</li>
 *   <li>{@code Data.SamplingRate} — sample rate of the IRs</li>
 *   <li>{@code Data.Delay} — ITD delay values [M × R]</li>
 * </ul>
 *
 * <p>Supports HDF5 superblock version 0 and 2 with contiguous data layout.
 * For full SOFA compatibility with complex HDF5 structures, the Foreign
 * Function &amp; Memory API (JEP 454) can be used with libmysofa as an
 * alternative — no JNI required.</p>
 */
public final class SofaFileParser {

    private static final byte[] HDF5_SIGNATURE = {
            (byte) 0x89, 'H', 'D', 'F', '\r', '\n', 0x1A, '\n'
    };

    private static final long UNDEFINED_ADDRESS = -1L;

    private SofaFileParser() {
        // utility class
    }

    /**
     * Parses a SOFA file and returns the HRTF dataset.
     *
     * @param sofaFile path to the SOFA file
     * @return the parsed HRTF data
     * @throws IOException if the file cannot be read or is not a valid SOFA file
     */
    public static HrtfData parse(Path sofaFile) throws IOException {
        try (FileChannel channel = FileChannel.open(sofaFile, StandardOpenOption.READ)) {
            long fileSize = channel.size();
            if (fileSize < HDF5_SIGNATURE.length) {
                throw new IOException("File too small to be a valid SOFA/HDF5 file");
            }
            ByteBuffer buffer = channel.map(FileChannel.MapMode.READ_ONLY, 0, fileSize);
            buffer.order(ByteOrder.LITTLE_ENDIAN);
            return parseHdf5(buffer, sofaFile.getFileName().toString());
        }
    }

    /**
     * Builds an {@link HrtfData} from pre-extracted raw arrays.
     *
     * <p>Useful for testing, interop with alternative data sources, or
     * loading from non-SOFA formats.</p>
     *
     * @param profileName     human-readable profile name
     * @param sampleRate      impulse response sample rate in Hz
     * @param sourcePositions source directions as {@code [M][3]} (azimuth, elevation, distance)
     * @param impulseResponses HRIR data as {@code [M][R][N]}
     * @param delays          ITD delay data as {@code [M][R]} in fractional samples
     * @return the constructed HRTF data
     */
    public static HrtfData fromRawData(String profileName, double sampleRate,
                                       double[][] sourcePositions,
                                       double[][][] impulseResponses,
                                       double[][] delays) {
        int m = sourcePositions.length;
        List<SphericalCoordinate> positions = new ArrayList<>(m);
        for (double[] pos : sourcePositions) {
            if (pos.length < 3) {
                throw new IllegalArgumentException("Each source position must have 3 components");
            }
            positions.add(new SphericalCoordinate(pos[0], pos[1], pos[2]));
        }

        int r = impulseResponses.length > 0 ? impulseResponses[0].length : 0;
        int n = (r > 0 && impulseResponses[0].length > 0) ? impulseResponses[0][0].length : 0;

        float[][][] irFloat = new float[m][r][n];
        for (int i = 0; i < m; i++) {
            for (int j = 0; j < r; j++) {
                for (int k = 0; k < n; k++) {
                    irFloat[i][j][k] = (float) impulseResponses[i][j][k];
                }
            }
        }

        float[][] delayFloat = new float[m][r];
        for (int i = 0; i < m; i++) {
            for (int j = 0; j < Math.min(r, delays[i].length); j++) {
                delayFloat[i][j] = (float) delays[i][j];
            }
        }

        return new HrtfData(profileName, sampleRate, positions, irFloat, delayFloat);
    }

    // ---- HDF5 binary parsing ------------------------------------------------

    private static HrtfData parseHdf5(ByteBuffer buf, String fileName) throws IOException {
        validateSignature(buf);
        int superblockVersion = Byte.toUnsignedInt(buf.get(8));

        return switch (superblockVersion) {
            case 0, 1 -> parseWithSuperblockV0(buf, fileName);
            case 2, 3 -> parseWithSuperblockV2(buf, fileName);
            default -> throw new IOException(
                    "Unsupported HDF5 superblock version: " + superblockVersion);
        };
    }

    private static void validateSignature(ByteBuffer buf) throws IOException {
        for (int i = 0; i < HDF5_SIGNATURE.length; i++) {
            if (buf.get(i) != HDF5_SIGNATURE[i]) {
                throw new IOException("Invalid HDF5 signature — not a SOFA file");
            }
        }
    }

    // ---- Superblock version 0 -----------------------------------------------

    private static HrtfData parseWithSuperblockV0(ByteBuffer buf, String fileName)
            throws IOException {
        int offsetSize = Byte.toUnsignedInt(buf.get(13));
        int lengthSize = Byte.toUnsignedInt(buf.get(14));

        int pos = 24; // skip to base address
        long baseAddress = readOffset(buf, pos, offsetSize);
        pos += offsetSize;
        pos += offsetSize; // free space info address
        pos += offsetSize; // end of file address
        pos += offsetSize; // driver info address

        // Root group symbol table entry
        long rootLinkNameOffset = readOffset(buf, pos, offsetSize);
        pos += offsetSize;
        long rootObjectHeaderAddr = readOffset(buf, pos, offsetSize);

        Map<String, long[]> rootEntries = readGroupV0(buf, rootObjectHeaderAddr,
                offsetSize, lengthSize);
        return extractSofaData(buf, rootEntries, offsetSize, lengthSize, fileName);
    }

    // ---- Superblock version 2 -----------------------------------------------

    private static HrtfData parseWithSuperblockV2(ByteBuffer buf, String fileName)
            throws IOException {
        int offsetSize = Byte.toUnsignedInt(buf.get(9));
        int lengthSize = Byte.toUnsignedInt(buf.get(10));

        int pos = 12; // after version + sizes + flags
        long baseAddress = readOffset(buf, pos, offsetSize);
        pos += offsetSize;
        pos += offsetSize; // superblock extension address
        pos += offsetSize; // end of file address
        long rootGroupAddr = readOffset(buf, pos, offsetSize);

        Map<String, long[]> rootEntries = readGroupV2(buf, (int) rootGroupAddr,
                offsetSize, lengthSize);
        return extractSofaData(buf, rootEntries, offsetSize, lengthSize, fileName);
    }

    // ---- Group reading (v0 — symbol table) ----------------------------------

    private static Map<String, long[]> readGroupV0(ByteBuffer buf, long objHeaderAddr,
                                                   int offsetSize, int lengthSize)
            throws IOException {
        int pos = (int) objHeaderAddr;
        int ohVersion = Byte.toUnsignedInt(buf.get(pos));
        if (ohVersion != 1) {
            throw new IOException("Unsupported object header version for v0 group: " + ohVersion);
        }
        pos += 2; // version + reserved
        int numMessages = Short.toUnsignedInt(buf.getShort(pos));
        pos += 2;
        pos += 4; // reference count
        int headerDataSize = buf.getInt(pos);
        pos += 4;

        long btreeAddr = UNDEFINED_ADDRESS;
        long heapAddr = UNDEFINED_ADDRESS;

        int msgStart = pos;
        for (int m = 0; m < numMessages; m++) {
            int msgType = Short.toUnsignedInt(buf.getShort(pos));
            int msgSize = Short.toUnsignedInt(buf.getShort(pos + 2));
            pos += 8; // type(2) + size(2) + flags(1) + reserved(3)
            if (msgType == 0x0011) { // Symbol Table message
                btreeAddr = readOffset(buf, pos, offsetSize);
                heapAddr = readOffset(buf, pos + offsetSize, offsetSize);
            }
            pos += msgSize;
        }

        if (btreeAddr == UNDEFINED_ADDRESS) {
            throw new IOException("Root group does not contain a symbol table message");
        }

        return readSymbolTable(buf, btreeAddr, heapAddr, offsetSize, lengthSize);
    }

    private static Map<String, long[]> readSymbolTable(ByteBuffer buf, long btreeAddr,
                                                       long heapAddr, int offsetSize,
                                                       int lengthSize)
            throws IOException {
        // Read local heap to get the name data segment
        int heapPos = (int) heapAddr;
        // Signature "HEAP"
        if (buf.get(heapPos) != 'H' || buf.get(heapPos + 1) != 'E'
                || buf.get(heapPos + 2) != 'A' || buf.get(heapPos + 3) != 'P') {
            throw new IOException("Invalid local heap signature");
        }
        heapPos += 4 + 1 + 3; // signature + version + reserved
        long dataSegSize = readLength(buf, heapPos, lengthSize);
        heapPos += lengthSize;
        heapPos += lengthSize; // free list offset
        long dataSegAddr = readOffset(buf, heapPos, offsetSize);

        // Read B-tree to find symbol table nodes
        Map<String, long[]> entries = new LinkedHashMap<>();
        readBtreeNode(buf, (int) btreeAddr, (int) dataSegAddr, offsetSize, lengthSize, entries);
        return entries;
    }

    private static void readBtreeNode(ByteBuffer buf, int nodeAddr, int heapDataAddr,
                                      int offsetSize, int lengthSize,
                                      Map<String, long[]> entries) throws IOException {
        int pos = nodeAddr;
        // Signature "TREE"
        if (buf.get(pos) != 'T' || buf.get(pos + 1) != 'R'
                || buf.get(pos + 2) != 'E' || buf.get(pos + 3) != 'E') {
            throw new IOException("Invalid B-tree signature at " + nodeAddr);
        }
        pos += 4;
        int nodeType = Byte.toUnsignedInt(buf.get(pos));
        int nodeLevel = Byte.toUnsignedInt(buf.get(pos + 1));
        int entriesUsed = Short.toUnsignedInt(buf.getShort(pos + 2));
        pos += 4;
        pos += offsetSize; // left sibling
        pos += offsetSize; // right sibling

        if (nodeLevel > 0) {
            // Internal node — keys and child pointers
            for (int i = 0; i <= entriesUsed; i++) {
                pos += lengthSize; // key
                if (i < entriesUsed) {
                    long childAddr = readOffset(buf, pos, offsetSize);
                    pos += offsetSize;
                    readBtreeNode(buf, (int) childAddr, heapDataAddr,
                            offsetSize, lengthSize, entries);
                }
            }
        } else {
            // Leaf node — points to symbol table nodes (SNODs)
            for (int i = 0; i <= entriesUsed; i++) {
                pos += lengthSize; // key
                if (i < entriesUsed) {
                    long snodAddr = readOffset(buf, pos, offsetSize);
                    pos += offsetSize;
                    readSnod(buf, (int) snodAddr, heapDataAddr, offsetSize, entries);
                }
            }
        }
    }

    private static void readSnod(ByteBuffer buf, int snodAddr, int heapDataAddr,
                                 int offsetSize, Map<String, long[]> entries) throws IOException {
        int pos = snodAddr;
        // Signature "SNOD"
        if (buf.get(pos) != 'S' || buf.get(pos + 1) != 'N'
                || buf.get(pos + 2) != 'O' || buf.get(pos + 3) != 'D') {
            throw new IOException("Invalid symbol table node signature at " + snodAddr);
        }
        pos += 4 + 1 + 1; // signature + version + reserved
        int numEntries = Short.toUnsignedInt(buf.getShort(pos));
        pos += 2;

        int entrySize = offsetSize + offsetSize + 4 + 4 + 16; // link name offset + obj header addr + cache type + reserved + scratch
        for (int i = 0; i < numEntries; i++) {
            long nameOffset = readOffset(buf, pos, offsetSize);
            pos += offsetSize;
            long objectHeaderAddr = readOffset(buf, pos, offsetSize);
            pos += offsetSize;
            int cacheType = buf.getInt(pos);
            pos += 4 + 4 + 16; // cache type + reserved + scratch

            String name = readNullTerminatedString(buf, (int) (heapDataAddr + nameOffset));
            if (!name.isEmpty()) {
                entries.put(name, new long[]{objectHeaderAddr, cacheType});
            }
        }
    }

    // ---- Group reading (v2 — link messages in object header) ----------------

    private static Map<String, long[]> readGroupV2(ByteBuffer buf, int objHeaderAddr,
                                                   int offsetSize, int lengthSize)
            throws IOException {
        int pos = objHeaderAddr;
        // Check for "OHDR" signature (v2 object header)
        if (buf.get(pos) != 'O' || buf.get(pos + 1) != 'H'
                || buf.get(pos + 2) != 'D' || buf.get(pos + 3) != 'R') {
            throw new IOException("Invalid v2 object header signature at " + objHeaderAddr);
        }
        pos += 4;
        int ohVersion = Byte.toUnsignedInt(buf.get(pos));
        if (ohVersion != 2) {
            throw new IOException("Expected object header v2, got: " + ohVersion);
        }
        pos++;
        int flags = Byte.toUnsignedInt(buf.get(pos));
        pos++;

        // Optional timestamps
        if ((flags & 0x04) != 0) {
            pos += 16; // 4 timestamps × 4 bytes
        }
        // Optional attribute counts
        if ((flags & 0x10) != 0) {
            pos += 4; // max compact + min dense (2+2)
        }

        int sizeFieldBytes = 1 << (flags & 0x03);
        long chunkSize = readVariableSize(buf, pos, sizeFieldBytes);
        pos += sizeFieldBytes;

        Map<String, long[]> entries = new LinkedHashMap<>();
        int chunkEnd = pos + (int) chunkSize;

        while (pos < chunkEnd) {
            int msgType = Byte.toUnsignedInt(buf.get(pos));
            int msgSize = Short.toUnsignedInt(buf.getShort(pos + 1));
            int msgFlags = Byte.toUnsignedInt(buf.get(pos + 3));
            pos += 4; // type(1) + size(2) + flags(1)

            if ((msgFlags & 0x04) != 0) { // creation order present
                pos += 2;
            }

            if (msgType == 0x06) { // Link message
                readLinkMessage(buf, pos, msgSize, offsetSize, entries);
            }
            pos += msgSize;
        }

        return entries;
    }

    private static void readLinkMessage(ByteBuffer buf, int pos, int msgSize,
                                        int offsetSize, Map<String, long[]> entries) {
        int start = pos;
        int version = Byte.toUnsignedInt(buf.get(pos));
        int flags = Byte.toUnsignedInt(buf.get(pos + 1));
        pos += 2;

        int linkType = (flags & 0x08) != 0 ? Byte.toUnsignedInt(buf.get(pos++)) : 0;

        if ((flags & 0x04) != 0) {
            pos += 8; // creation order
        }

        if ((flags & 0x10) != 0) {
            pos += 1; // link name character set
        }

        int nameLenFieldSize = 1 << (flags & 0x03);
        long nameLen = readVariableSize(buf, pos, nameLenFieldSize);
        pos += nameLenFieldSize;

        byte[] nameBytes = new byte[(int) nameLen];
        for (int i = 0; i < nameLen; i++) {
            nameBytes[i] = buf.get(pos + i);
        }
        String name = new String(nameBytes);
        pos += (int) nameLen;

        if (linkType == 0) { // hard link
            long targetAddr = readOffset(buf, pos, offsetSize);
            entries.put(name, new long[]{targetAddr, 0});
        }
    }

    // ---- Dataset extraction -------------------------------------------------

    private static HrtfData extractSofaData(ByteBuffer buf, Map<String, long[]> rootEntries,
                                            int offsetSize, int lengthSize, String fileName)
            throws IOException {
        // Look for the Data group (subgroup) or direct datasets
        Map<String, long[]> dataEntries = rootEntries;
        if (rootEntries.containsKey("Data")) {
            long dataGroupAddr = rootEntries.get("Data")[0];
            int ohVersion = Byte.toUnsignedInt(buf.get((int) dataGroupAddr));
            if (ohVersion == 1) {
                dataEntries = readGroupV0(buf, dataGroupAddr, offsetSize, lengthSize);
            } else {
                dataEntries = readGroupV2(buf, (int) dataGroupAddr, offsetSize, lengthSize);
            }
        }

        // Extract SourcePosition
        long[] spEntry = rootEntries.get("SourcePosition");
        if (spEntry == null) {
            throw new IOException("SOFA file missing required dataset: SourcePosition");
        }
        DatasetInfo spInfo = readDatasetHeader(buf, (int) spEntry[0], offsetSize, lengthSize);
        double[] spFlat = readDoubleData(buf, spInfo);

        // Extract Data.IR
        long[] irEntry = dataEntries.get("IR");
        if (irEntry == null) {
            irEntry = rootEntries.get("Data.IR");
        }
        if (irEntry == null) {
            throw new IOException("SOFA file missing required dataset: Data.IR");
        }
        DatasetInfo irInfo = readDatasetHeader(buf, (int) irEntry[0], offsetSize, lengthSize);
        double[] irFlat = readDoubleData(buf, irInfo);

        // Extract Data.SamplingRate
        long[] srEntry = dataEntries.get("SamplingRate");
        if (srEntry == null) {
            srEntry = rootEntries.get("Data.SamplingRate");
        }
        if (srEntry == null) {
            throw new IOException("SOFA file missing required dataset: Data.SamplingRate");
        }
        DatasetInfo srInfo = readDatasetHeader(buf, (int) srEntry[0], offsetSize, lengthSize);
        double[] srFlat = readDoubleData(buf, srInfo);
        double sampleRate = srFlat[0];

        // Extract Data.Delay (optional — default to zeros)
        double[] delayFlat = null;
        int[] delayDims = null;
        long[] delayEntry = dataEntries.get("Delay");
        if (delayEntry == null) {
            delayEntry = rootEntries.get("Data.Delay");
        }
        if (delayEntry != null) {
            DatasetInfo delayInfo = readDatasetHeader(buf, (int) delayEntry[0],
                    offsetSize, lengthSize);
            delayFlat = readDoubleData(buf, delayInfo);
            delayDims = delayInfo.dimensions;
        }

        // Reshape and construct HrtfData
        return reshapeToHrtfData(fileName, sampleRate, spFlat, spInfo.dimensions,
                irFlat, irInfo.dimensions, delayFlat, delayDims);
    }

    private record DatasetInfo(int[] dimensions, int elementSize, long dataAddress, long dataSize) {}

    private static DatasetInfo readDatasetHeader(ByteBuffer buf, int objHeaderAddr,
                                                 int offsetSize, int lengthSize)
            throws IOException {
        int pos = objHeaderAddr;
        int ohVersion = Byte.toUnsignedInt(buf.get(pos));

        if (ohVersion == 1) {
            return readDatasetHeaderV1(buf, pos, offsetSize, lengthSize);
        } else if (buf.get(pos) == 'O' && buf.get(pos + 1) == 'H'
                && buf.get(pos + 2) == 'D' && buf.get(pos + 3) == 'R') {
            return readDatasetHeaderV2(buf, pos, offsetSize, lengthSize);
        }
        throw new IOException("Unrecognized object header format at " + objHeaderAddr);
    }

    private static DatasetInfo readDatasetHeaderV1(ByteBuffer buf, int start,
                                                   int offsetSize, int lengthSize)
            throws IOException {
        int pos = start;
        pos += 2; // version + reserved
        int numMessages = Short.toUnsignedInt(buf.getShort(pos));
        pos += 2;
        pos += 4; // ref count
        pos += 4; // header size

        int[] dimensions = null;
        int elementSize = 8; // default to float64
        long dataAddress = UNDEFINED_ADDRESS;
        long dataSize = 0;

        for (int m = 0; m < numMessages; m++) {
            int msgType = Short.toUnsignedInt(buf.getShort(pos));
            int msgSize = Short.toUnsignedInt(buf.getShort(pos + 2));
            int msgPos = pos + 8;

            if (msgType == 0x0001) { // Dataspace
                dimensions = readDataspace(buf, msgPos, lengthSize);
            } else if (msgType == 0x0003) { // Datatype
                elementSize = readDatatypeSize(buf, msgPos);
            } else if (msgType == 0x0008) { // Data Layout
                long[] layout = readDataLayout(buf, msgPos, offsetSize, lengthSize);
                dataAddress = layout[0];
                dataSize = layout[1];
            }
            pos += 8 + msgSize;
        }

        if (dimensions == null) {
            dimensions = new int[]{(int) (dataSize / elementSize)};
        }
        return new DatasetInfo(dimensions, elementSize, dataAddress, dataSize);
    }

    private static DatasetInfo readDatasetHeaderV2(ByteBuffer buf, int start,
                                                   int offsetSize, int lengthSize)
            throws IOException {
        int pos = start + 5; // OHDR signature(4) + version(1)
        int flags = Byte.toUnsignedInt(buf.get(pos));
        pos++;

        if ((flags & 0x04) != 0) {
            pos += 16; // timestamps
        }
        if ((flags & 0x10) != 0) {
            pos += 4; // attribute counts
        }

        int sizeFieldBytes = 1 << (flags & 0x03);
        long chunkSize = readVariableSize(buf, pos, sizeFieldBytes);
        pos += sizeFieldBytes;

        int chunkEnd = pos + (int) chunkSize;
        int[] dimensions = null;
        int elementSize = 8;
        long dataAddress = UNDEFINED_ADDRESS;
        long dataSize = 0;

        while (pos < chunkEnd) {
            int msgType = Byte.toUnsignedInt(buf.get(pos));
            int msgSize = Short.toUnsignedInt(buf.getShort(pos + 1));
            int msgFlags = Byte.toUnsignedInt(buf.get(pos + 3));
            int msgPos = pos + 4;
            if ((msgFlags & 0x04) != 0) {
                msgPos += 2; // creation order
            }

            if (msgType == 0x0001) {
                dimensions = readDataspace(buf, msgPos, lengthSize);
            } else if (msgType == 0x0003) {
                elementSize = readDatatypeSize(buf, msgPos);
            } else if (msgType == 0x0008) {
                long[] layout = readDataLayout(buf, msgPos, offsetSize, lengthSize);
                dataAddress = layout[0];
                dataSize = layout[1];
            }
            pos += 4 + msgSize;
            if ((msgFlags & 0x04) != 0) {
                pos += 2;
            }
        }

        if (dimensions == null) {
            dimensions = new int[]{(int) (dataSize / elementSize)};
        }
        return new DatasetInfo(dimensions, elementSize, dataAddress, dataSize);
    }

    private static int[] readDataspace(ByteBuffer buf, int pos, int lengthSize) {
        int version = Byte.toUnsignedInt(buf.get(pos));
        int ndims = Byte.toUnsignedInt(buf.get(pos + 1));
        int offset = (version == 1) ? pos + 8 : pos + 4;
        int[] dims = new int[ndims];
        for (int i = 0; i < ndims; i++) {
            dims[i] = (int) readLength(buf, offset + i * lengthSize, lengthSize);
        }
        return dims;
    }

    private static int readDatatypeSize(ByteBuffer buf, int pos) {
        return buf.getInt(pos + 4); // class+version(1) + class-bits(3) + size(4)
    }

    private static long[] readDataLayout(ByteBuffer buf, int pos,
                                         int offsetSize, int lengthSize) {
        int version = Byte.toUnsignedInt(buf.get(pos));
        long address = UNDEFINED_ADDRESS;
        long size = 0;

        if (version == 3) {
            int layoutClass = Byte.toUnsignedInt(buf.get(pos + 1));
            if (layoutClass == 1) { // contiguous
                address = readOffset(buf, pos + 2, offsetSize);
                size = readLength(buf, pos + 2 + offsetSize, lengthSize);
            } else if (layoutClass == 0) { // compact
                int compactSize = Short.toUnsignedInt(buf.getShort(pos + 2));
                address = pos + 4; // data follows inline
                size = compactSize;
            }
        }
        return new long[]{address, size};
    }

    private static double[] readDoubleData(ByteBuffer buf, DatasetInfo info) throws IOException {
        if (info.dataAddress == UNDEFINED_ADDRESS) {
            throw new IOException("Dataset data address is undefined (chunked storage not supported)");
        }
        int numElements = (int) (info.dataSize / info.elementSize);
        double[] data = new double[numElements];
        int pos = (int) info.dataAddress;

        if (info.elementSize == 8) {
            for (int i = 0; i < numElements; i++) {
                data[i] = buf.getDouble(pos + i * 8);
            }
        } else if (info.elementSize == 4) {
            for (int i = 0; i < numElements; i++) {
                data[i] = buf.getFloat(pos + i * 4);
            }
        } else {
            throw new IOException("Unsupported element size: " + info.elementSize);
        }
        return data;
    }

    // ---- Reshape flat arrays into HrtfData ----------------------------------

    private static HrtfData reshapeToHrtfData(String profileName, double sampleRate,
                                              double[] spFlat, int[] spDims,
                                              double[] irFlat, int[] irDims,
                                              double[] delayFlat, int[] delayDims) {
        int m = spDims.length >= 1 ? spDims[0] : spFlat.length / 3;
        int r = irDims.length >= 2 ? irDims[1] : 2;
        int n = irDims.length >= 3 ? irDims[2] : irFlat.length / (m * r);

        List<SphericalCoordinate> positions = new ArrayList<>(m);
        for (int i = 0; i < m; i++) {
            double azimuth = spFlat[i * 3];
            double elevation = spFlat[i * 3 + 1];
            double distance = spFlat[i * 3 + 2];
            positions.add(new SphericalCoordinate(azimuth, elevation, distance));
        }

        float[][][] ir = new float[m][r][n];
        for (int i = 0; i < m; i++) {
            for (int j = 0; j < r; j++) {
                for (int k = 0; k < n; k++) {
                    ir[i][j][k] = (float) irFlat[(i * r + j) * n + k];
                }
            }
        }

        float[][] delays = new float[m][r];
        if (delayFlat != null) {
            int delayM = (delayDims != null && delayDims.length >= 1) ? delayDims[0] : 1;
            for (int i = 0; i < m; i++) {
                int srcIdx = Math.min(i, delayM - 1);
                for (int j = 0; j < r; j++) {
                    delays[i][j] = (float) delayFlat[srcIdx * r + j];
                }
            }
        }

        return new HrtfData(profileName, sampleRate, positions, ir, delays);
    }

    // ---- Binary reading helpers ---------------------------------------------

    private static long readOffset(ByteBuffer buf, int pos, int size) {
        return switch (size) {
            case 2 -> Short.toUnsignedLong(buf.getShort(pos));
            case 4 -> Integer.toUnsignedLong(buf.getInt(pos));
            case 8 -> buf.getLong(pos);
            default -> throw new IllegalArgumentException("Unsupported offset size: " + size);
        };
    }

    private static long readLength(ByteBuffer buf, int pos, int size) {
        return readOffset(buf, pos, size);
    }

    private static long readVariableSize(ByteBuffer buf, int pos, int size) {
        return switch (size) {
            case 1 -> Byte.toUnsignedLong(buf.get(pos));
            case 2 -> Short.toUnsignedLong(buf.getShort(pos));
            case 4 -> Integer.toUnsignedLong(buf.getInt(pos));
            case 8 -> buf.getLong(pos);
            default -> throw new IllegalArgumentException("Unsupported variable size: " + size);
        };
    }

    private static String readNullTerminatedString(ByteBuffer buf, int pos) {
        int start = pos;
        while (buf.get(pos) != 0) {
            pos++;
        }
        byte[] bytes = new byte[pos - start];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = buf.get(start + i);
        }
        return new String(bytes);
    }
}
