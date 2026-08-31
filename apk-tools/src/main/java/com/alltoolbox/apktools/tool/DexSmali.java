package com.alltoolbox.apktools.tool;

import java.io.File;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;

/**
 * 轻量 DEX 反编译器：解析 DEX 头部、字符串表、类型表、proto 表、field/method 表、
 * 类定义与 class_data，输出结构级 smali 骨架（类/字段/方法声明与签名）。
 *
 * 说明：本实现产出可读的 smali 类结构骨架，不含逐指令字节码反汇编（完整反汇编
 * 请使用电脑端 baksmali / apktool）。
 */
public final class DexSmali {

    // header 各表偏移（header_size=112）
    private int stringIdsSize, stringIdsOff;
    private int typeIdsSize, typeIdsOff;
    private int protoIdsSize, protoIdsOff;
    private int fieldIdsSize, fieldIdsOff;
    private int methodIdsSize, methodIdsOff;
    private int classDefsSize, classDefsOff;

    private final byte[] data;

    private DexSmali(byte[] data) {
        this.data = data;
    }

    public static String decompile(File dexFile) throws Exception {
        byte[] bytes;
        try (RandomAccessFile raf = new RandomAccessFile(dexFile, "r")) {
            bytes = new byte[(int) raf.length()];
            raf.readFully(bytes);
        }
        return decompile(bytes);
    }

    public static String decompile(byte[] dexData) throws Exception {
        if (dexData.length < 112) throw new IllegalStateException("DEX 文件过小，无法解析");
        DexSmali d = new DexSmali(dexData);
        d.parseHeader();
        return d.render();
    }

    private void parseHeader() {
        stringIdsSize = readInt(56);
        stringIdsOff = readInt(60);
        typeIdsSize = readInt(64);
        typeIdsOff = readInt(68);
        protoIdsSize = readInt(72);
        protoIdsOff = readInt(76);
        fieldIdsSize = readInt(80);
        fieldIdsOff = readInt(84);
        methodIdsSize = readInt(88);
        methodIdsOff = readInt(92);
        classDefsSize = readInt(96);
        classDefsOff = readInt(100);
    }

    private int readInt(int off) {
        if (off < 0 || off + 4 > data.length) return 0;
        return (data[off] & 0xFF) | ((data[off + 1] & 0xFF) << 8)
                | ((data[off + 2] & 0xFF) << 16) | ((data[off + 3] & 0xFF) << 24);
    }

    // ---------------- 字符串 ----------------

    private String stringAt(int idx) {
        if (idx < 0 || idx >= stringIdsSize) return "";
        int strOff = readInt(stringIdsOff + idx * 4);
        return readMutf8(strOff);
    }

    private String readMutf8(int off) {
        if (off < 0 || off >= data.length) return "";
        int p = off;
        while (p < data.length && (data[p] & 0x80) != 0) p++;
        p++; // 跳过 utf16_size uleb128
        StringBuilder sb = new StringBuilder();
        while (p < data.length && data[p] != 0) {
            int b = data[p] & 0xFF;
            if (b < 0x80) {
                sb.append((char) b);
                p++;
            } else if ((b & 0xE0) == 0xC0 && p + 1 < data.length) {
                int c = ((b & 0x1F) << 6) | (data[p + 1] & 0x3F);
                sb.append((char) c);
                p += 2;
            } else if ((b & 0xF0) == 0xE0 && p + 2 < data.length) {
                int c = ((b & 0x0F) << 12) | ((data[p + 1] & 0x3F) << 6) | (data[p + 2] & 0x3F);
                sb.append((char) c);
                p += 3;
            } else {
                sb.append((char) b);
                p++;
            }
        }
        return sb.toString();
    }

    private String typeAt(int typeIdx) {
        if (typeIdx < 0 || typeIdx >= typeIdsSize) return "";
        int stringIdx = readInt(typeIdsOff + typeIdx * 4);
        return stringAt(stringIdx);
    }

    /** 简化类型展示：/ 转 .，去掉外层 L;/[]。 */
    private String prettyType(String t) {
        if (t == null || t.isEmpty()) return "void";
        // 数组
        int arr = 0;
        while (t.startsWith("[")) {
            arr++;
            t = t.substring(1);
        }
        String simple;
        if (t.startsWith("L") && t.endsWith(";")) {
            simple = t.substring(1, t.length() - 1).replace('/', '.');
        } else {
            switch (t) {
                case "V": simple = "void"; break;
                case "Z": simple = "boolean"; break;
                case "B": simple = "byte"; break;
                case "S": simple = "short"; break;
                case "C": simple = "char"; break;
                case "I": simple = "int"; break;
                case "J": simple = "long"; break;
                case "F": simple = "float"; break;
                case "D": simple = "double"; break;
                default: simple = t;
            }
        }
        StringBuilder sb = new StringBuilder(simple);
        for (int i = 0; i < arr; i++) sb.append("[]");
        return sb.toString();
    }

    // ---------------- proto ----------------

    private List<String> protoParams(int protoIdx) {
        List<String> out = new ArrayList<>();
        if (protoIdx < 0 || protoIdx >= protoIdsSize) return out;
        int protoOff = readInt(protoIdsOff + protoIdx * 12);
        if (protoOff <= 0) return out;
        int paramsOff = readInt(protoOff + 8);
        if (paramsOff <= 0) return out;
        UInt p = new UInt(paramsOff);
        int count = (int) uleb128(p);
        for (int i = 0; i < count; i++) {
            int ti = readInt(p.value);
            out.add(prettyType(typeAt(ti)));
            p.value += 4;
        }
        return out;
    }

    private String protoReturn(int protoIdx) {
        if (protoIdx < 0 || protoIdx >= protoIdsSize) return "void";
        int protoOff = readInt(protoIdsOff + protoIdx * 12);
        if (protoOff <= 0) return "void";
        int retTypeIdx = readInt(protoOff + 4);
        return prettyType(typeAt(retTypeIdx));
    }

    // ---------------- 渲染 ----------------

    private String render() {
        StringBuilder out = new StringBuilder();
        out.append("# 由 AllToolbox DEX 反编译器生成 (结构级 smali 骨架)\n");
        out.append("# 类数: ").append(classDefsSize)
                .append("  字段数: ").append(fieldIdsSize)
                .append("  方法数: ").append(methodIdsSize).append("\n\n");

        for (int ci = 0; ci < classDefsSize; ci++) {
            int base = classDefsOff + ci * 32;
            int classIdx = readInt(base);
            int accessFlags = readInt(base + 4);
            int superIdx = readInt(base + 8);
            int sourceFileIdx = readInt(base + 16);
            int classDataOff = readInt(base + 24);

            String className = prettyType(typeAt(classIdx));
            String superName = typeAt(superIdx);
            if (superName.startsWith("L")) superName = prettyType(superName);
            String srcFile = sourceFileIdx >= 0 ? stringAt(sourceFileIdx) : "";

            out.append(".class ").append(accessString(accessFlags)).append(className).append('\n');
            if (superName != null && !superName.isEmpty() && !superName.equals("java.lang.Object")) {
                out.append(".super ").append(superName).append('\n');
            }
            if (srcFile != null && !srcFile.isEmpty()) {
                out.append(".source \"").append(srcFile).append("\"\n");
            }
            if (classDataOff > 0) {
                out.append(renderClassData(classDataOff));
            }
            out.append('\n');
        }
        return out.toString();
    }

    private String accessString(int flags) {
        int[] names = {0x1, 0x2, 0x4, 0x8, 0x10, 0x20, 0x100, 0x200};
        String[] strs = {"public", "private", "protected", "static", "final",
                "synchronized", "interface", "abstract"};
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < names.length; i++) {
            if ((flags & names[i]) != 0) {
                if (sb.length() > 0) sb.append(' ');
                sb.append(strs[i]);
            }
        }
        if (sb.length() > 0) sb.append(' ');
        return sb.toString();
    }

    private String renderClassData(int off) {
        StringBuilder sb = new StringBuilder();
        UInt p = new UInt(off);
        long staticFields = uleb128(p);
        long instanceFields = uleb128(p);
        long directMethods = uleb128(p);
        long virtualMethods = uleb128(p);

        int curField = 0;
        long totalFields = staticFields + instanceFields;
        for (long i = 0; i < totalFields; i++) {
            curField += (int) uleb128(p);
            int fa = (int) uleb128(p);
            renderField(sb, curField, fa);
        }

        int curMethod = 0;
        for (long i = 0; i < directMethods; i++) {
            curMethod += (int) uleb128(p);
            int ma = (int) uleb128(p);
            uleb128(p); // code_off
            renderMethod(sb, curMethod, ma);
        }
        curMethod = 0;
        for (long i = 0; i < virtualMethods; i++) {
            curMethod += (int) uleb128(p);
            int ma = (int) uleb128(p);
            uleb128(p); // code_off
            renderMethod(sb, curMethod, ma);
        }
        return sb.toString();
    }

    private void renderField(StringBuilder sb, int fieldIdx, int access) {
        int base = fieldIdsOff + fieldIdx * 8;
        int typeIdx = readInt(base + 2) & 0xFFFF;
        int nameIdx = readInt(base + 4);
        sb.append("    .field ").append(accessString(access))
                .append(stringAt(nameIdx)).append(" : ")
                .append(prettyType(typeAt(typeIdx))).append('\n');
    }

    private void renderMethod(StringBuilder sb, int methodIdx, int access) {
        int base = methodIdsOff + methodIdx * 8;
        int protoIdx = readInt(base + 2) & 0xFFFF;
        int nameIdx = readInt(base + 4);
        String name = stringAt(nameIdx);
        sb.append("    .method ").append(accessString(access)).append(name)
                .append('(').append(String.join(", ", protoParams(protoIdx))).append(')')
                .append(prettyType(protoReturn(protoIdx)))
                .append("\n    .locals 0\n")
                .append("    # (结构级骨架，字节码见 PC 端 baksmali)\n")
                .append("    .end method\n");
    }

    private long uleb128(UInt p) {
        long result = 0;
        int shift = 0;
        while (true) {
            int b = data[p.value] & 0xFF;
            p.value++;
            result |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) break;
            shift += 7;
            if (shift > 35) break;
        }
        return result;
    }

    private static final class UInt {
        int value;
        UInt(int v) { this.value = v; }
    }
}