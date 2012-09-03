package ws.palladian.retrieval.search.web;

import static org.junit.Assert.assertEquals;

import java.util.List;

import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

import ws.palladian.helper.constants.Language;

/**
 * Created with IntelliJ IDEA.
 * User: sebastian
 * Date: 16.08.12
 * Time: 18:16
 * To change this template use File | Settings | File Templates.
 */
public class GoogleImageSearcherTest {

    private GoogleImageSearcher googleImageSearcher;

    @Before
    public void setup() {
        this.googleImageSearcher = new GoogleImageSearcher();
    }

    @Test
    @Ignore
    public void testSearch() throws Exception {
        // Google seems to send a maximum of 64 pics
        List<WebImageResult> cats = googleImageSearcher.search("dog", 50, Language.ENGLISH);
        assertEquals(50, cats.size());
    }
}