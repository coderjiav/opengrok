/*
 * CDDL HEADER START
 *
 * The contents of this file are subject to the terms of the
 * Common Development and Distribution License (the "License").
 * You may not use this file except in compliance with the License.
 *
 * See LICENSE.txt included in this distribution for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing Covered Code, include this CDDL HEADER in each
 * file and include the License file at LICENSE.txt.
 * If applicable, add the following below this CDDL HEADER, with the
 * fields enclosed by brackets "[]" replaced with your own identifying
 * information: Portions Copyright [yyyy] [name of copyright owner]
 *
 * CDDL HEADER END
 */

/*
 * Copyright (c) 2008, 2017, Oracle and/or its affiliates. All rights reserved.
 * Portions Copyright (c) 2018, Chris Fraire <cfraire@me.com>.
 */

package org.opensolaris.opengrok.search.context;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;
import java.util.TreeSet;
import org.apache.lucene.document.Document;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.highlight.InvalidTokenOffsetsException;
import org.junit.After;
import org.junit.AfterClass;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.opensolaris.opengrok.analysis.FileAnalyzer;
import org.opensolaris.opengrok.analysis.plain.PlainAnalyzerFactory;
import org.opensolaris.opengrok.configuration.RuntimeEnvironment;
import org.opensolaris.opengrok.history.HistoryGuru;
import org.opensolaris.opengrok.index.Indexer;
import org.opensolaris.opengrok.util.TestRepository;
import org.opensolaris.opengrok.history.RepositoryFactory;
import org.opensolaris.opengrok.search.QueryBuilder;
import org.opensolaris.opengrok.search.SearchEngine;
import static org.opensolaris.opengrok.util.CustomAssertions.assertLinesEqual;

/**
 * Represents a container for tests of {@link SearchEngine} with
 * {@link ContextFormatter} etc.
 * <p>
 * Derived from Trond Norbye's {@code SearchEngineTest}
 */
public class SearchAndContextFormatterTest {

    private static RuntimeEnvironment env;
    private static TestRepository repository;
    private static File configFile;
    private static boolean skip = false;

    @BeforeClass
    public static void setUpClass() throws Exception {
        repository = new TestRepository();
        repository.create(HistoryGuru.class.getResourceAsStream(
            "repositories.zip"));

        env = RuntimeEnvironment.getInstance();
        env.setCtags(System.getProperty(
            "org.opensolaris.opengrok.analysis.Ctags", "ctags"));
        env.setSourceRoot(repository.getSourceRoot());
        env.setDataRoot(repository.getDataRoot());
        RepositoryFactory.initializeIgnoredNames(env);

        if (env.validateExuberantCtags()) {
            env.setSourceRoot(repository.getSourceRoot());
            env.setDataRoot(repository.getDataRoot());
            env.setVerbose(false);
            env.setHistoryEnabled(false);
            Indexer.getInstance().prepareIndexer(env, true, true,
                new TreeSet<>(Arrays.asList(new String[]{"/c"})),
                false, false, null, null, new ArrayList<>(), false);
            Indexer.getInstance().doIndexerExecution(true, null, null);
        } else {
            System.out.println(
                "Skipping test. Could not find a ctags I could use in path.");
            skip = true;
        }

        configFile = File.createTempFile("configuration", ".xml");
        env.writeConfiguration(configFile);
        RuntimeEnvironment.getInstance().readConfiguration(new File(
            configFile.getAbsolutePath()));
    }

    @AfterClass
    public static void tearDownClass() throws Exception {
        repository.destroy();
        configFile.delete();
        skip = false;
    }

    @Before
    public void setUp() {
    }

    @After
    public void tearDown() {
    }

    @Test
    public void testSearch() throws IOException, InvalidTokenOffsetsException {
        if (skip) {
            return;
        }

        SearchEngine instance;
        int noHits;

        instance = new SearchEngine();
        instance.setFreetext("embedded");
        instance.setFile("main.c");
        noHits = instance.search();
        assertTrue("noHits should be positive", noHits > 0);
        String[] frags = getFirstFragments(instance);
        assertNotNull("getFirstFragments() should return something", frags);
        assertTrue("frags should have one element", frags.length == 1);

        final String CTX =
            "<a class=\"s\" href=\"/source/svn/c/main.c#9\"><span class=\"l\">9</span>    /*</a><br/>" +
            "<a class=\"s\" href=\"/source/svn/c/main.c#10\"><span class=\"l\">10</span>    Multi line comment, with <b>embedded</b> strange characters: &lt; &gt; &amp;,</a><br/>" +
            "<a class=\"s\" href=\"/source/svn/c/main.c#11\"><span class=\"l\">11</span>    email address: testuser@example.com and even an URL:</a><br/>";
        assertLinesEqual("ContextFormatter output", CTX, frags[0]);
        instance.destroy();
    }

    private String[] getFirstFragments(SearchEngine instance)
            throws IOException, InvalidTokenOffsetsException {

        ContextArgs args = new ContextArgs((short)1, (short)10);

        /*
         * The following `anz' should go unused, but UnifiedHighlighter demands
         * an analyzer "even if in some circumstances it isn't used."
         */
        PlainAnalyzerFactory fac = PlainAnalyzerFactory.DEFAULT_INSTANCE;
        FileAnalyzer anz = fac.getAnalyzer();

        ContextFormatter formatter = new ContextFormatter(args);
        OGKUnifiedHighlighter uhi = new OGKUnifiedHighlighter(env,
            instance.getSearcher(), anz);
        uhi.setBreakIterator(() -> new StrictLineBreakIterator());
        uhi.setFormatter(formatter);

        ScoreDoc[] docs = instance.scoreDocs();
        for (int i = 0; i < docs.length; ++i) {
            int docid = docs[i].doc;
            Document doc = instance.doc(docid);

            String path = doc.get(QueryBuilder.PATH);
            System.out.println(path);
            formatter.setUrl("/source" + path);

            for (String contextField :
                instance.getQueryBuilder().getContextFields()) {

                Map<String,String[]> res = uhi.highlightFields(
                    new String[]{contextField}, instance.getQueryObject(),
                    new int[] {docid}, new int[] {10});
                String[] frags = res.getOrDefault(contextField, null);
                if (frags != null) {
                    return frags;
                }
            }
        }
        return null;
    }
}
