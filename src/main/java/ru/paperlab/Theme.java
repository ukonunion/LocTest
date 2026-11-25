package ru.paperlab;
import java.awt.*;
import javax.swing.*;
public final class Theme {
    public static void defaults(boolean dark) {
        Color bg=dark?new Color(32,33,37):new Color(245,245,245), fg=dark?new Color(235,235,235):new Color(25,25,25);
        for(String key:new String[]{"Panel","OptionPane","CheckBox","Label","Button","ComboBox","TextField","TextArea","Spinner","Viewport","ScrollPane"}) { UIManager.put(key+".background",bg);UIManager.put(key+".foreground",fg); }
        UIManager.put("TextField.caretForeground",fg);UIManager.put("TextArea.caretForeground",fg);
    }
    public static void apply(Component c, boolean dark) {
        c.setBackground(dark?new Color(32,33,37):new Color(245,245,245));c.setForeground(dark?new Color(235,235,235):new Color(25,25,25));
        if(c instanceof Container group) for(Component child:group.getComponents())apply(child,dark);
        if(c instanceof ConsoleView console) console.theme(dark);
        if(c instanceof SystemMonitor monitor) monitor.theme(dark);
    }
}
