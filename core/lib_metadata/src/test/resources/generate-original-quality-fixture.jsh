import java.awt.color.*;
import java.awt.image.*;
import java.io.*;
import javax.imageio.*;
var file = new File("core/core_exif/src/test/resources/original-quality.jpg");
ImageIO.write(new BufferedImage(5000, 16, BufferedImage.TYPE_INT_RGB), "jpeg", file);
var encoded = java.nio.file.Files.readAllBytes(file.toPath());
var profile = ICC_Profile.getInstance(ColorSpace.CS_sRGB).getData();
var output = new DataOutputStream(new FileOutputStream(file));
output.write(encoded, 0, 2);
output.writeShort(0xFFE2);
output.writeShort(profile.length + 16);
output.write("ICC_PROFILE\u0000".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
output.write(new byte[]{1, 1});
output.write(profile);
output.write(encoded, 2, encoded.length - 2);
output.close();
/exit
