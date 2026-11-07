import io.github.jqssun.airplay.audio.PlayerPalette;
import java.util.Arrays;
import java.util.Random;
public class PlayerPaletteTest {
    static void check(boolean value) { if (!value) throw new AssertionError(); }
    static int blend(int a,int b,double t) {
        int out=0xff000000;
        for(int shift:new int[]{0,8,16})out|=(int)Math.round((a>>shift&255)*(1-t)+(b>>shift&255)*t)<<shift;
        return out;
    }
    static void verify(PlayerPalette p) {
        int[] backgrounds={p.base,p.glow,p.secondaryGlow};
        for(int a:backgrounds)for(int b:backgrounds)for(int step=0;step<=10;step++) {
            int bg=blend(a,b,step/10.);
            check(PlayerPalette.luminance(bg)>.55);
            check(PlayerPalette.contrast(p.ink,bg)>=7);
            check(PlayerPalette.contrast(p.body,bg)>=4.5);
            check(PlayerPalette.contrast(p.accent,bg)>=3);
        }
    }
    public static void main(String[] args) {
        verify(PlayerPalette.fromPixels(new int[0]));
        verify(PlayerPalette.fromPixels(new int[]{0,0x00ff0000}));
        for(int color:new int[]{0xff000000,0xffffffff,0xff808080,0xffff0000,0xff00ff00,0xff0000ff,0xffffff00}) {
            int[] pixels=new int[1024];Arrays.fill(pixels,color);verify(PlayerPalette.fromPixels(pixels));
            if((color&255)==(color>>8&255)&&(color&255)==(color>>16&255)) {
                int text=PlayerPalette.fromPixels(pixels).ink;
                check((text&255)==(text>>8&255)&&(text&255)==(text>>16&255));
            }
        }
        Random random=new Random(144);
        for(int i=0;i<2000;i++) {
            int[] pixels=new int[64];int a=0xff000000|random.nextInt(0x1000000),b=0xff000000|random.nextInt(0x1000000);
            for(int j=0;j<pixels.length;j++)pixels[j]=j%3==0?a:b;
            verify(PlayerPalette.fromPixels(pixels));
        }
        System.out.println("PASS: light surfaces, contrast across gradients, monochrome, transparency, 2000 mixed palettes");
    }
}
