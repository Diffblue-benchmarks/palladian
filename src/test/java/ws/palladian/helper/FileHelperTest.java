package ws.palladian.helper;

import junit.framework.TestCase;

import org.junit.Test;

import ws.palladian.helper.FileHelper;

/**
 * Test cases for the FileHelper class.
 * 
 * @author David Urbansky
 */
public class FileHelperTest extends TestCase {

    public FileHelperTest(String name) {
        super(name);
    }

    @Test
    public void testGetFileName() {
        assertEquals("abc", FileHelper.getFileName("data/temp/abc.jpg"));
        assertEquals("abc", FileHelper.getFileName("abc.jpg"));
        assertEquals("abc", FileHelper.getFileName("abc"));
    }

    @Test
    public void testGetFilePath() {
        assertEquals("data/temp/", FileHelper.getFilePath("data/temp/abc.jpg"));
        assertEquals("", FileHelper.getFilePath("abc.jpg"));
    }

    @Test
    public void testGetFileType() {
        assertEquals("jpg", FileHelper.getFileType("data/temp/abc.jpg"));
        assertEquals("jpg", FileHelper.getFileType("abc.jpg"));
        assertEquals("jpg", FileHelper.getFileType("http://www.test.com/abc.jpg"));
        assertEquals("aspx", FileHelper.getFileType("http://www.test.com/abc.aspx?param1=123"));
        assertEquals("aspx", FileHelper.getFileType("http://www.test.com/abc.aspx?param1=123&Web.Offset=abc"));
    }

    @Test
    public void testAppendToFileName() {
        assertEquals("data/temp/abc_0.jpg", FileHelper.appendToFileName("data/temp/abc.jpg", "_0"));
        assertEquals("abcX123.jpg", FileHelper.appendToFileName("abc.jpg", "X123"));
    }
}