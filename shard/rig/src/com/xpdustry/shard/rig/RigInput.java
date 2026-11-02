package com.xpdustry.shard.rig;

import javax.imageio.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.*;
import java.io.*;
import java.nio.charset.*;
import java.util.*;

/**
 * Real X11 input for one display ($DISPLAY), through XTEST via {@link Robot}, plus screenshots. One long-lived
 * process per client, so a key press costs microseconds instead of a JVM start. It reads one command per line on
 * stdin and answers {@code ok [...]} or {@code err <message>} on stdout.
 *
 * <pre>
 * move X Y              pointer to screen pixel X,Y (origin top left)
 * down|up BUTTON        left, middle or right
 * wheel N               N notches; positive scrolls down (Mindustry zooms out)
 * keydown|keyup KEY     a java.awt.event.KeyEvent VK_ name without the prefix: W, ENTER, ESCAPE, SHIFT, F1, COMMA...
 * type TEXT             the rest of the line, with shift for capitals and US-layout symbols
 * shot PATH             PNG of the whole display; answers with mean, deviation and colour count
 * </pre>
 */
public final class RigInput{
    static final String plain = "`1234567890-=[]\\;',./", shifted = "~!@#$%^&*()_+{}|:\"<>?";
    static final int[] plainCodes = {KeyEvent.VK_BACK_QUOTE, KeyEvent.VK_1, KeyEvent.VK_2, KeyEvent.VK_3, KeyEvent.VK_4, KeyEvent.VK_5,
        KeyEvent.VK_6, KeyEvent.VK_7, KeyEvent.VK_8, KeyEvent.VK_9, KeyEvent.VK_0, KeyEvent.VK_MINUS, KeyEvent.VK_EQUALS,
        KeyEvent.VK_OPEN_BRACKET, KeyEvent.VK_CLOSE_BRACKET, KeyEvent.VK_BACK_SLASH, KeyEvent.VK_SEMICOLON, KeyEvent.VK_QUOTE,
        KeyEvent.VK_COMMA, KeyEvent.VK_PERIOD, KeyEvent.VK_SLASH};

    public static void main(String[] args) throws Exception{
        var robot = new Robot();
        robot.setAutoDelay(0);
        var in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        var out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        out.println("ok ready");
        for(String line; (line = in.readLine()) != null; ){
            try{
                out.println(run(robot, line));
            }catch(Exception e){
                out.println("err " + String.valueOf(e).replace('\n', ' '));
            }
        }
    }

    static String run(Robot robot, String line) throws IOException{
        String[] a = line.split(" ", 2);
        String arg = a.length > 1 ? a[1] : "";
        switch(a[0]){
            case "move" -> {
                String[] xy = arg.split(" ");
                robot.mouseMove(Integer.parseInt(xy[0]), Integer.parseInt(xy[1]));
            }
            case "down" -> robot.mousePress(button(arg));
            case "up" -> robot.mouseRelease(button(arg));
            case "wheel" -> {
                int n = Integer.parseInt(arg);
                for(int i = 0; i < Math.abs(n); i++){
                    robot.mouseWheel(Integer.signum(n));
                    robot.delay(60);
                }
            }
            case "keydown" -> robot.keyPress(key(arg));
            case "keyup" -> robot.keyRelease(key(arg));
            case "type" -> {
                for(char c : arg.toCharArray()) type(robot, c);
            }
            case "shot" -> {
                return "ok " + shot(robot, new File(arg));
            }
            default -> {
                return "err unknown command " + a[0];
            }
        }
        return "ok";
    }

    static int button(String name){
        return switch(name){
            case "left" -> InputEvent.BUTTON1_DOWN_MASK;
            case "middle" -> InputEvent.BUTTON2_DOWN_MASK;
            case "right" -> InputEvent.BUTTON3_DOWN_MASK;
            default -> throw new IllegalArgumentException("unknown button " + name);
        };
    }

    static int key(String name){
        String n = name.toUpperCase(Locale.ROOT);
        n = switch(n){
            case "ESC" -> "ESCAPE";
            case "CTRL" -> "CONTROL";
            case "RETURN" -> "ENTER";
            default -> n;
        };
        try{
            return KeyEvent.class.getField("VK_" + n).getInt(null);
        }catch(ReflectiveOperationException e){
            throw new IllegalArgumentException("unknown key " + name);
        }
    }

    static void type(Robot robot, char c){
        boolean shift = Character.isUpperCase(c);
        int code;
        int i;
        if(c == ' '){
            code = KeyEvent.VK_SPACE;
        }else if(Character.isLetter(c) && c < 128){
            code = KeyEvent.getExtendedKeyCodeForChar(Character.toLowerCase(c));
        }else if((i = plain.indexOf(c)) >= 0){
            code = plainCodes[i];
        }else if((i = shifted.indexOf(c)) >= 0){
            code = plainCodes[i];
            shift = true;
        }else{
            throw new IllegalArgumentException("cannot type '" + c + "' on a US layout");
        }
        if(shift) robot.keyPress(KeyEvent.VK_SHIFT);
        robot.keyPress(code);
        robot.delay(15);
        robot.keyRelease(code);
        if(shift) robot.keyRelease(KeyEvent.VK_SHIFT);
        robot.delay(25);
    }

    /** Saves the display and measures it, so the rig can call a black or single-colour frame a failure. */
    static String shot(Robot robot, File file) throws IOException{
        BufferedImage image = robot.createScreenCapture(new Rectangle(Toolkit.getDefaultToolkit().getScreenSize()));
        file.getParentFile().mkdirs();
        ImageIO.write(image, "png", file);
        double sum = 0, sq = 0;
        int n = 0;
        var colours = new BitSet(1 << 18);
        for(int y = 0; y < image.getHeight(); y += 2){
            for(int x = 0; x < image.getWidth(); x += 2){
                int rgb = image.getRGB(x, y);
                double luma = 0.299 * (rgb >> 16 & 255) + 0.587 * (rgb >> 8 & 255) + 0.114 * (rgb & 255);
                sum += luma;
                sq += luma * luma;
                n++;
                colours.set((rgb >> 18 & 63) << 12 | (rgb >> 10 & 63) << 6 | (rgb >> 2 & 63));
            }
        }
        double mean = sum / n;
        return String.format(Locale.ROOT, "mean=%.1f std=%.1f colours=%d", mean, Math.sqrt(Math.max(0, sq / n - mean * mean)), colours.cardinality());
    }
}
