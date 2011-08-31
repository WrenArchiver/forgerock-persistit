/**
 * Copyright (C) 2011 Akiban Technologies Inc.
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License, version 3,
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see http://www.gnu.org/licenses.
 */

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.CharBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.persistit.exception.PersistitException;

public class AsciiDocIndex {
    private final static String NOISY_STRINGS[] = { "\r", "\n", "<B>", "</B>",
            "<b>", "</b>", "<CODE>", "</CODE>", "<code>", "</code>", "<TT>",
            "</TT>", "<tt>", "</tt>", "<FONT>", "</FONT>", "<font>", "</font>" };

    // Charset and decoder for ISO-8859-15
    private final static Charset charset = Charset.forName("ISO-8859-15");

    private final static CharsetDecoder decoder = charset.newDecoder();
    //
    // Regex Pattern to pull various attributes and fields out of the anchor
    // tags in Javadoc index-NN.html files.
    //
    private final static Pattern PATTERN = Pattern
            .compile(".*(<A HREF=\"(.*?)\"( *title=\"(.*?)\")?.*?>(.*)</A>)");

    private int _count;

    final SortedMap<String, String> classMap = new TreeMap<String, String>();
    final SortedMap<String, String> methodMap = new TreeMap<String, String>();

    /**
     * Builds a JDocSearch index from the specified Javadoc file or directory.
     * If the supplied <tt>File</tt> object is a file, then read and index the
     * content of that one file. If it is a directory, read the files in that
     * directory and index them.
     * 
     * @param file
     * 
     * @return The count of indexable terms in the file or directory
     * 
     * @throws IOException
     * 
     * @throws PersistitException
     */
    public int buildIndex(String pathName, String base) throws IOException {
        File file = new File(pathName);

        // The index generated by the standard Javadoc Doclet is either
        // at the root of the api tree, in a file called index-all.html, or
        // in a subdirectory called index-files. This code tries each case.
        //
        if (file.exists() && file.isDirectory()
                && !file.getPath().endsWith("index-files")) {
            File indexAll = new File(file, "index-all.html");
            File indexDir = new File(file, "index-files");
            if (indexAll.exists() && !indexAll.isDirectory()) {
                file = indexAll;
            } else if (indexDir.exists() && indexDir.isDirectory()) {
                file = indexDir;
            }
        }
        if (file.exists()) {
            if (base == null) {
                base = file.getParent();
            }
            if (file.isDirectory()) {
                indexOneDirectory(file, base);
            } else {
                indexOneFile(file, base);
            }
        } else {
            throw new IllegalArgumentException(
                    "Requires the name of a Javadoc API index file, "
                            + "or of a directory containing Javadoc API index files.");
        }
        return _count;
    }

    public void indexOneDirectory(File indexDir, String base)
            throws IOException {
        File[] indexFiles = indexDir.listFiles();
        for (int i = 0; i < indexFiles.length; i++) {
            indexOneFile(indexFiles[i], base);
        }
    }

    public void indexOneFile(File file, String base) throws IOException {
        try {
            System.out.println("Indexing " + file);

            FileChannel fc = new FileInputStream(file).getChannel();

            MappedByteBuffer bb = fc.map(FileChannel.MapMode.READ_ONLY, 0,
                    fc.size());
            CharBuffer cb = decoder.decode(bb);
            Matcher matcher = PATTERN.matcher(cb);

            while (matcher.find()) {
                String wholeTag = matcher.group(1);
                String href = matcher.group(2);
                String url = base + "/" + fixDotSlash(href);
                String title = matcher.group(4);
                String text = matcher.group(5);

                indexOneTerm(wholeTag, href, url, title, text);

            }
            fc.close();
        } catch (IOException e) {
            System.err.println();
            e.printStackTrace();
            throw e;
        }
    }

    private void indexOneTerm(String wholeTag, String href, String url,
            String title, String text) {
        text = cleanupNoise(text);
        href = fixDotSlash(href);

        int pHtml = href.lastIndexOf(".html");

        int pPackageSummary = href.indexOf("/package-summary");
        if (pPackageSummary > 0) {
            //
            // Enumerate the package name segments
            // The HREF starts with "./", which is chopped off here.
            //
            String packageName = href.substring(0, pPackageSummary).replace(
                    '/', '.');

            int q = -1;
            while (q < packageName.length()) {
                int p = q + 1;
                q = packageName.indexOf('.', p);
                if (q < 0)
                    q = packageName.length();
                String term = packageName.substring(p, q);
                writeTermToPersistit("Package", term, url, packageName);
            }
            return;
        }

        if (href.startsWith("com/") || href.startsWith("java/")
                || href.startsWith("javax/") || href.startsWith("org/")
                || href.startsWith("COM/") || href.startsWith("ORG/")) {
            int pHash = href.indexOf('#');
            if (pHash == -1) {
                // This is a class or interface name
                String category = "Class";
                if (title.startsWith("interface"))
                    category = "Interface";
                int pSlash = href.lastIndexOf('/', pHtml - 1);
                // String className = href.substring(pSlash + 1, pHtml);
                String className = href.substring(0, pHtml).replace('/', '.');
                writeTermToPersistit(category, className, url, text);
                return;
            }

            else {
                String className = href.substring(0, pHtml).replace('/', '.');
                String name = href.substring(pHash + 1);
                int pLeftParen = name.indexOf('(');
                if (pLeftParen == -1) {
                    //
                    // This is a field or a constant. We'll call it a constant
                    // if
                    // it is spelled in upper case.
                    //
                    String uCaseName = name.toUpperCase();
                    String category = name.equals(uCaseName) ? "Constant"
                            : "Field";
                    String displayText = name + " in " + className;
                    writeTermToPersistit(category, name, url, displayText);
                    return;
                } else {
                    //
                    // This is a method name. We will index it as a method,
                    // and then if it conforms to the pattern for property
                    // set/get methods, we'll also index the property name.
                    //
                    int pRightParen = name.indexOf(')', pLeftParen);
                    if (pRightParen == -1) {
                        System.out.println("Missing right paren");
                        System.out.println(wholeTag);
                        return;
                    }
                    String paramList = name.substring(pLeftParen + 1,
                            pRightParen).trim();
                    // String term = name.substring(0, pLeftParen);
                    String term = (href.substring(0, pHtml) + href
                            .substring(pHtml + 5)).replace('/', '.');
                    writeTermToPersistit("Method", term, url, name);
                    String displayText = name + " in " + className;
                    if (name.startsWith("get") && paramList.length() == 0
                            || name.startsWith("is") && paramList.length() == 0
                            || name.startsWith("set") && paramList.length() > 0
                            && paramList.indexOf(',') == -1) {
                        term = term.substring(name.startsWith("is") ? 2 : 3);
                        writeTermToPersistit("Property", term, url, displayText);
                    }
                    return;
                }
            }
        }
    }

    private void writeTermToPersistit(final String type, final String term,
            final String url, final String text) {
        if ("Method".equals(type)) {
            methodMap.put(term, url);
        }
        if ("Class".equals(type) || "Interface".equals(type)) {
            classMap.put(term, url);
        }
    }

    private String fixDotSlash(String url) {
        if (url.startsWith("./"))
            return url.substring(2);
        else
            return url;
    }

    private String cleanupNoise(String term) {
        boolean changed = false;
        StringBuffer sb = new StringBuffer(term);
        for (int i = 0; i < NOISY_STRINGS.length; i++) {
            String tag = NOISY_STRINGS[i];
            for (int p; (p = sb.indexOf(tag)) >= 0;) {
                sb.delete(p, p + tag.length());
                changed = true;
            }
        }
        return changed ? sb.toString() : term;
    }

    public void index(final String javaDocPathname) throws Exception {
        String base = "http://www.akiban.com/documentation/apidocs";
        buildIndex(javaDocPathname, base);
    }

    public SortedMap<String, String> getClassMap() {
        return classMap;
    }

    public SortedMap<String, String> getMethodMap() {
        return methodMap;
    }
}