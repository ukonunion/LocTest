package ru.paperlab;

import javax.swing.*;
import javax.swing.text.*;
import java.awt.*;
import java.util.regex.Pattern;

public final class ConsoleView extends JTextPane {
    private static final Pattern ERROR = Pattern.compile("(?i)(?:\\b(?:ERROR|FATAL|SEVERE)\\b|^ОШИБКА:)");
    private static final Pattern WARN = Pattern.compile("(?i)\\bWARN(?:ING)?\\b");
    private boolean dark = true;
    public ConsoleView() { setEditable(false); setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13)); }
    static int severity(String line) { return ERROR.matcher(line).find() ? 2 : WARN.matcher(line).find() ? 1 : 0; }
    private SimpleAttributeSet style(int level) {
        SimpleAttributeSet a = new SimpleAttributeSet();
        StyleConstants.setForeground(a, level == 2 ? (dark ? new Color(255,100,100) : new Color(185,20,20)) : level == 1 ? (dark ? new Color(255,211,65) : new Color(132,98,0)) : (dark ? new Color(222,226,232) : new Color(32,35,40)));
        if (level == 1 && !dark) StyleConstants.setBackground(a, new Color(255,247,199));
        return a;
    }
    public void appendLine(String value) {
        try {
            StyledDocument doc = getStyledDocument(); boolean follow = getCaretPosition() >= doc.getLength() - 1;
            for (String line : value.replaceAll("\\x1B\\[[0-9;]*[A-Za-z]", "").split("\\R", -1)) doc.insertString(doc.getLength(), line + "\n", style(severity(line)));
            if (doc.getLength() > 250000) { String text = doc.getText(0, Math.min(60000, doc.getLength())); int end = text.indexOf('\n',50000); doc.remove(0,end < 0 ? 50000 : end+1); }
            if (follow) setCaretPosition(doc.getLength());
        } catch (BadLocationException e) { throw new IllegalStateException(e); }
    }
    public void theme(boolean dark) {
        this.dark = dark; setBackground(dark ? new Color(24,25,29) : Color.WHITE); setCaretColor(dark ? Color.WHITE : Color.BLACK);
        StyledDocument doc = getStyledDocument(); Element root = doc.getDefaultRootElement();
        try { for(int i=0;i<root.getElementCount();i++){Element e=root.getElement(i);int length=Math.min(e.getEndOffset(),doc.getLength())-e.getStartOffset(); if(length>0)doc.setCharacterAttributes(e.getStartOffset(),length,style(severity(doc.getText(e.getStartOffset(),length))),true);} } catch(BadLocationException ignored){}
    }
}
