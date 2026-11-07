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
        int[] backgrounds={p.base,p.glow,p.secondaryGlow,0xffa3a3a3};
        for(int a:backgrounds)for(int b:backgrounds)for(int step=0;step<=10;step++) {
            int bg=blend(a,b,step/10.);
            check(PlayerPalette.luminance(bg)>.35);
            check(PlayerPalette.contrast(p.ink,bg)>=7);
            check(PlayerPalette.contrast(p.body,bg)>=4.5);
            check(PlayerPalette.contrast(p.accent,bg)>=3);
        }
    }
    static void verifyVibrantSelection() {
        int red=0xffff3018, cyan=0xff00d9ff;
        int[] pixels=new int[1024]; Arrays.fill(pixels,0xffad8865);
        Arrays.fill(pixels,0,48,red);
        PlayerPalette p=PlayerPalette.fromPixels(pixels);
        check(p.vibrantGlow==red); // A small vivid accent beats a broad muted cover.
        pixels[100]=cyan;
        p=PlayerPalette.fromPixels(pixels);
        check(p.vibrantGlow==red && p.vibrantSecondaryGlow==0xffffffff); // Ignore a stray pixel.
        Arrays.fill(pixels,0xfff4f4f4);
        Arrays.fill(pixels,0,48,red); Arrays.fill(pixels,48,80,cyan);
        p=PlayerPalette.fromPixels(pixels);
        check((p.vibrantGlow==red && p.vibrantSecondaryGlow==cyan) ||
              (p.vibrantGlow==cyan && p.vibrantSecondaryGlow==red)); // White borders do not wash out either hue.
        verify(p); // Readability is guaranteed by the continuous light colors themselves.
        Arrays.fill(pixels,0xff606060); pixels[0]=0x00ff0000;
        p=PlayerPalette.fromPixels(pixels);
        check(p.vibrantGlow==0xff606060 && p.vibrantSecondaryGlow==0xffffffff);
    }
    public static void main(String[] args) {
        verifyVibrantSelection();
        verify(PlayerPalette.fromPixels(new int[0]));
        verify(PlayerPalette.fromPixels(new int[]{0,0x00ff0000}));
        for(int color:new int[]{0xff000000,0xffffffff,0xff808080,0xffff0000,0xff00ff00,0xff0000ff,0xffffff00}) {
            int[] pixels=new int[1024];Arrays.fill(pixels,color);verify(PlayerPalette.fromPixels(pixels));
            PlayerPalette single=PlayerPalette.fromPixels(pixels);
            check(single.secondaryGlow==0xffffffff);
            if(color==0xffffffff) check(single.base==0xffffffff && single.glow==0xffffffff);
            else check(single.glow!=single.secondaryGlow);
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
        System.out.println("PASS: vivid cover accents, white borders, noise rejection, reading contrast, monochrome, transparency, 2000 mixed palettes");
    }
}
