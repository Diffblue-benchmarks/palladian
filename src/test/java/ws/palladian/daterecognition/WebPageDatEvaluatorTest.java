/**
 * 
 */
package ws.palladian.daterecognition;

import static org.junit.Assert.assertEquals;

import org.junit.Ignore;
import org.junit.Test;

import ws.palladian.control.AllTests;

/**
 * FIXME {@link #testGetAllBestRatedDate()} and {@link #testGetAllDates()} fail, after Document parser has been changed
 * to produce lower case tag names.
 * 
 * @author Martin Gregor
 * @author Klemens Muthmann
 */
public class WebPageDatEvaluatorTest {

    /**
     * Test method for {@link ws.palladian.daterecognition.WebPageDateEvaluatorOld#getBestRatedDate()}.
     */

    @Test
    public void testGetBestRatedDate() {
    if (AllTests.ALL_TESTS) {
            String url = WebPageDatEvaluatorTest.class.getResource("/webPages/dateExtraction/zeit2.htm").getFile();
            WebPageDateEvaluator wpde = new WebPageDateEvaluator();
            wpde.setUrl(url);
            wpde.evaluate();
            assertEquals("2010-09-02", wpde.getBestRatedDate().getNormalizedDateString());

            url = WebPageDatEvaluatorTest.class.getResource("/webPages/dateExtraction/zeit1.htm").getFile();
            wpde.setUrl(url);
            wpde.evaluate();
            assertEquals("2010-08-22", wpde.getBestRatedDate().getNormalizedDateString());

            // url = WebPageDatEvaluatorTest.class.getResource("/webPages/dateExtraction/kullin.htm").getFile();
            // wpde.setUrl(url);
            // wpde.evaluate();
            // assertEquals("2010-05-28 22:41", wpde.getBestRatedDate().getNormalizedDateString());
        }
    }

    /**
     * Test method for {@link ws.palladian.daterecognition.WebPageDateEvaluatorOld#getAllBestRatedDate()}.
     */
    @Test
    public void testGetAllBestRatedDate() {
        String url = WebPageDatEvaluatorTest.class.getResource("/webPages/dateExtraction/zeit2.htm").getFile();
        WebPageDateEvaluator wpde = new WebPageDateEvaluator();
        wpde.setUrl(url);
        wpde.evaluate();
        assertEquals(1, wpde.getAllBestRatedDate().size());

        url = WebPageDatEvaluatorTest.class.getResource("/webPages/dateExtraction/zeit1.htm").getFile();
        wpde.setUrl(url);
        wpde.evaluate();
        assertEquals(1, wpde.getAllBestRatedDate().size());

        url = WebPageDatEvaluatorTest.class.getResource("/webPages/dateExtraction/kullin.htm").getFile();
        wpde.setUrl(url);
        wpde.evaluate();
        // System.out.println(wpde.getAllDates());
        assertEquals(1, wpde.getAllBestRatedDate().size());
    }

    /**
     * Test method for {@link ws.palladian.daterecognition.WebPageDateEvaluatorOld#getAllDates()}.
     */
    @Test
    public void testGetAllDates() {
        String url = WebPageDatEvaluatorTest.class.getResource("/webPages/dateExtraction/zeit2.htm").getFile();
        WebPageDateEvaluator wpde = new WebPageDateEvaluator();
        wpde.setUrl(url);
        wpde.evaluate();
        assertEquals(2, wpde.getAllDates().size());

        url = WebPageDatEvaluatorTest.class.getResource("/webPages/dateExtraction/zeit1.htm").getFile();
        wpde.setUrl(url);
        wpde.evaluate();
        assertEquals(5, wpde.getAllDates().size());

        url = WebPageDatEvaluatorTest.class.getResource("/webPages/dateExtraction/kullin.htm").getFile();
        wpde.setUrl(url);
        wpde.evaluate();
        assertEquals(12, wpde.getAllDates().size());
    }
}
