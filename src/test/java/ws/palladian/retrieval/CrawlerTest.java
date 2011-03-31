package ws.palladian.retrieval;

import junit.framework.TestCase;
import ws.palladian.control.AllTests;
import ws.palladian.extraction.PageAnalyzer;
import ws.palladian.helper.UrlHelper;
import ws.palladian.helper.html.XPathHelper;
import ws.palladian.retrieval.DocumentRetriever;
import ws.palladian.retrieval.TBODYFix;

/**
 * Test cases for the crawler.
 * 
 * @author David Urbansky
 * @author Philipp Katz
 * @author Klemens Muthmann
 */
public class CrawlerTest extends TestCase {

    public CrawlerTest(String name) {
        super(name);
    }

    public void testGetCleanURL() {
        assertEquals("amazon.com/", UrlHelper.getCleanURL("http://www.amazon.com/"));
        assertEquals("amazon.com/", UrlHelper.getCleanURL("http://amazon.com/"));
        assertEquals("amazon.com/", UrlHelper.getCleanURL("https://www.amazon.com/"));
        assertEquals("amazon.com", UrlHelper.getCleanURL("https://amazon.com"));
        assertEquals("amazon.com/", UrlHelper.getCleanURL("www.amazon.com/"));
        assertEquals("amazon.com/", UrlHelper.getCleanURL("amazon.com/"));
    }

    public void testGetDomain() {
        // System.out.println(crawler.getDomain("http://www.flashdevices.net/2008/02/updated-flash-enabled-devices.html",
        // false));
        assertEquals("http://www.flashdevices.net",
                UrlHelper.getDomain("http://www.flashdevices.net/2008/02/updated-flash-enabled-devices.html", true));
        assertEquals("www.flashdevices.net",
                UrlHelper.getDomain("http://www.flashdevices.net/2008/02/updated-flash-enabled-devices.html", false));

        assertEquals("http://blog.wired.com",
                UrlHelper.getDomain("http://blog.wired.com/underwire/2008/10/theres-yet-anot.html", true));
        assertEquals("blog.wired.com",
                UrlHelper.getDomain("http://blog.wired.com/underwire/2008/10/theres-yet-anot.html", false));

        // added by Philipp
        assertEquals("https://example.com", UrlHelper.getDomain("https://example.com/index.html"));
        assertEquals("", UrlHelper.getDomain(""));
        assertEquals("", UrlHelper.getDomain(null));
        assertEquals("", UrlHelper.getDomain("file:///test.html")); // TODO return localhost here?
        assertEquals("localhost", UrlHelper.getDomain("file://localhost/test.html", false));
    }


    public void testLinkHandling() {
        DocumentRetriever documentRetriever = new DocumentRetriever();
        documentRetriever.setDocument(CrawlerTest.class.getResource("/pageContentExtractor/test9.html").getFile());
        assertEquals("http://www.example.com/test.html", PageAnalyzer.getLinks(documentRetriever.getDocument(), true, true)
                .iterator().next());

        documentRetriever.setDocument(CrawlerTest.class.getResource("/pageContentExtractor/test10.html").getFile());
        assertEquals("http://www.example.com/test.html", PageAnalyzer.getLinks(documentRetriever.getDocument(), true, true)
                .iterator().next());
    }

    public void testMakeFullURL() {

        assertEquals("http://www.xyz.de/page.html", UrlHelper.makeFullURL("http://www.xyz.de", "", "page.html"));
        assertEquals("http://www.xyz.de/page.html", UrlHelper.makeFullURL("http://www.xyz.de", null, "page.html"));
        assertEquals("http://www.xyz.de/page.html",
                UrlHelper.makeFullURL("http://www.xyz.de/index.html", "", "page.html"));
        assertEquals("http://www.xyz.de/page.html",
                UrlHelper.makeFullURL("http://www.xyz.de/index.html", "/directory", "/page.html"));
        assertEquals("http://www.xyz.de/directory/page.html",
                UrlHelper.makeFullURL("http://www.xyz.de/index.html", "/directory", "./page.html"));
        assertEquals("http://www.xyz.de/directory/page.html",
                UrlHelper.makeFullURL("http://www.xyz.de/index.html", "/directory/directory", "../page.html"));

        assertEquals("http://www.abc.de/page.html",
                UrlHelper.makeFullURL("http://www.xyz.de", "", "http://www.abc.de/page.html"));
        assertEquals("http://www.abc.de/page.html",
                UrlHelper.makeFullURL("http://www.xyz.de", "http://www.abc.de/", "/page.html"));

        assertEquals("http://www.example.com/page.html",
                UrlHelper.makeFullURL("/some/file/path.html", "http://www.example.com/page.html"));
        assertEquals("", UrlHelper.makeFullURL("http://www.xyz.de", "mailto:example@example.com"));

        assertEquals("http://www.example.com/page.html",
                UrlHelper.makeFullURL(null, null, "http://www.example.com/page.html"));

        // when no linkUrl is supplied, we cannot determine the full URL, so just return an empty String.
        assertEquals("", UrlHelper.makeFullURL(null, "http://www.example.com", null));
        assertEquals("", UrlHelper.makeFullURL("http://www.example.com", null, null));
        assertEquals("", UrlHelper.makeFullURL(null, null, "/page.html"));

    }

    public void testNekoBugs() {

        // produces a StackOverflowError -- see
        // http://sourceforge.net/tracker/?func=detail&aid=3109537&group_id=195122&atid=952178
        // Crawler crawler = new Crawler();
        // Document doc =
        // crawler.getWebDocument(CrawlerTest.class.getResource("/webPages/NekoTestcase3109537.html").getFile());
        // assertNotNull(doc);

    }

    /**
     * Test undesired behavior from NekoHTML for which we introduced workarounds/fixes.
     * See {@link TBODYFix}.
     */
    public void testNekoWorkarounds() {

        DocumentRetriever crawler = new DocumentRetriever();
        assertEquals(
                3,
                XPathHelper.getXhtmlNodes(
                        crawler.getWebDocument(CrawlerTest.class.getResource("/webPages/NekoTableTestcase1.html")
                                .getFile()), "//TABLE/TR[1]/TD").size());
        assertEquals(
                3,
                XPathHelper.getXhtmlNodes(
                        crawler.getWebDocument(CrawlerTest.class.getResource("/webPages/NekoTableTestcase2.html")
                                .getFile()), "//TABLE/TBODY/TR[1]/TD").size());
        assertEquals(
                3,
                XPathHelper.getXhtmlNodes(
                        crawler.getWebDocument(CrawlerTest.class.getResource("/webPages/NekoTableTestcase3.html")
                                .getFile()), "//TABLE/TBODY/TR[1]/TD").size());
        assertEquals(
                3,
                XPathHelper.getXhtmlNodes(
                        crawler.getWebDocument(CrawlerTest.class.getResource("/webPages/NekoTableTestcase4.html")
                                .getFile()), "//TABLE/TR[1]/TD").size());

    }

    public void testParseXml() {

        DocumentRetriever crawler = new DocumentRetriever();

        // parse errors will yield in a null return
        assertNotNull(crawler
                .getXMLDocument(CrawlerTest.class.getResource("/xmlDocuments/invalid-chars.xml").getFile()));
        assertNotNull(crawler.getXMLDocument(CrawlerTest.class.getResource("/feeds/sourceforge02.xml").getFile()));
        assertNotNull(crawler.getXMLDocument(CrawlerTest.class.getResource("/feeds/feed061.xml").getFile()));

    }

}