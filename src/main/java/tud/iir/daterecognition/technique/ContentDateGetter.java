package tud.iir.daterecognition.technique;

import java.util.ArrayList;
import java.util.Iterator;

import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.w3c.dom.Text;

import tud.iir.daterecognition.DateGetterHelper;
import tud.iir.daterecognition.dates.ContentDate;
import tud.iir.helper.HTMLHelper;
import tud.iir.knowledge.KeyWords;

public class ContentDateGetter extends TechniqueDateGetter<ContentDate> {

    @Override
    public ArrayList<ContentDate> getDates() {
        ArrayList<ContentDate> result = new ArrayList<ContentDate>();
        if (document != null) {
            result = getContentDates(this.document);
        }
        return result;
    }

    private ArrayList<ContentDate> getContentDates(Document document) {
        ArrayList<ContentDate> dates = new ArrayList<ContentDate>();
        NodeList body = document.getElementsByTagName("body");
        String doc = HTMLHelper.htmlToString(body.item(0));

        if (body.getLength() > 0) {
            dates.addAll(enterTextnodes(body.item(0), doc, 0));
        }
        return dates;
    }

    private ArrayList<ContentDate> enterTextnodes(Node node, String doc, int depth) {

        ArrayList<ContentDate> dates = new ArrayList<ContentDate>();
        if (node.getNodeType() == Node.TEXT_NODE) {
            dates.addAll(checkTextnode((Text) node, doc, depth));
        } else {
            NodeList children = node.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                if (!children.item(i).getNodeName().equalsIgnoreCase("script")
                        && !children.item(i).getNodeName().equalsIgnoreCase("style"))
                    dates.addAll(enterTextnodes(children.item(i), doc, depth + 1));
            }
        }
        return dates;
    }

    private ArrayList<ContentDate> checkTextnode(Text node, String doc, int depth) {

        String text = node.getNodeValue();
        int index = doc.indexOf(text);

        Node parent = node.getParentNode();
        while (HTMLHelper.isSimpleElement(parent)) {
            parent = parent.getParentNode();
        }
        ArrayList<ContentDate> dates = new ArrayList<ContentDate>();
        Iterator<ContentDate> iterator = DateGetterHelper.findALLDates(text).iterator();
        while (iterator.hasNext()) {
            ContentDate date = iterator.next();
            date.set(ContentDate.STRUCTURE_DEPTH, depth);
            date.setTagNode(parent.toString());
            if (index != -1) {
                date.set(ContentDate.DATEPOS_IN_DOC, index + date.get(ContentDate.DATEPOS_IN_TAGTEXT));
            }
            date.setTag(parent.getNodeName());
            String keyword = DateGetterHelper.findNodeKeywordPart(parent, KeyWords.BODY_CONTENT_KEYWORDS);
            if (keyword != null) {
                date.setKeyword(keyword);
                date.set(ContentDate.KEYWORDLOCATION, ContentDate.KEY_LOC_ATTR);
            } else {
                DateGetterHelper.setNearestTextkeyword(text, date);
            }

            if (date.getKeyword() == null) {

                keyword = DateGetterHelper.findNodeKeywordPart(parent, KeyWords.DATE_BODY_STRUC);

                if (keyword != null) {
                    date.setKeyword(keyword);
                    date.set(ContentDate.KEYWORDLOCATION, ContentDate.KEY_LOC_ATTR);
                }
            }
            if (date.getKeyword() == null) {
                text = HTMLHelper.htmlToString(parent.getParentNode());
                DateGetterHelper.setNearestTextkeyword(text, date);
            }
            dates.add(date);

        }

        return dates;
    }

}
