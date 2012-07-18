package org.dna.mqtt.moquette.proto;

import java.io.UnsupportedEncodingException;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.apache.mina.core.buffer.IoBuffer;
import org.apache.mina.filter.codec.demux.MessageDecoderResult;
import org.dna.mqtt.moquette.proto.messages.AbstractMessage;

/**
 * Common utils methodd used in codecs.
 * 
 * @author andrea
 */
public class Utils {
    
     public static final int MAX_LENGTH_LIMIT = 268435455;

    /**
     * Read 2 bytes from in buffer first MSB, and then LSB returning as int.
     */
    static int readWord(IoBuffer in) {
        int msb = in.get() & 0x00FF; //remove sign extension due to casting
        int lsb = in.get() & 0x00FF;
        msb = (msb << 8) | lsb ;
        return msb;
    }
    
    /**
     * Writes as 2 bytes the int value into buffer first MSB, and then LSB.
     */
    static void writeWord(IoBuffer out, int value) {
        out.put((byte) ((value & 0xFF00) >> 8)); //msb
        out.put((byte) (value & 0x00FF)); //lsb
    }

    /**
     * Decode the variable remaining lenght as defined in MQTT v3.1 specification 
     * (section 2.1).
     * 
     * @return the decoded length or -1 if needed more data to decode.
     */
    static int decodeRemainingLenght(IoBuffer in) {
        int multiplier = 1;
        int value = 0;
        byte digit;
        do {
            if (in.remaining() < 1) {
                return -1;
            }
            digit = in.get();
            value += (digit & 0x7F) * multiplier;
            multiplier *= 128;
        } while ((digit & 0x80) != 0);
        return value;
    }
    
    /**
     * Encode the value in the format defined in specification as variable length
     * array.
     * 
     * @throws IllegalArgumentException if the value is not in the specification bounds
     *  [0..268435455].
     */
    static IoBuffer encodeRemainingLength(int value) throws IllegalAccessException {
        if (value > MAX_LENGTH_LIMIT || value < 0) {
            throw new IllegalAccessException("Value should in range 0.." + MAX_LENGTH_LIMIT + " found " + value);
        }

        IoBuffer encoded = IoBuffer.allocate(4);
        byte digit;
        do {
            digit = (byte) (value % 128);
            value = value / 128;
            // if there are more digits to encode, set the top bit of this digit
            if (value > 0) {
                digit = (byte) (digit | 0x80);
            }
            encoded.put(digit);
        } while (value > 0);
        encoded.flip();
        return encoded;
    }
    
    static MessageDecoderResult checkDecodable(byte type, IoBuffer in) {
        if (in.remaining() < 1) {
            return MessageDecoderResult.NEED_DATA;
        }
        byte h1 = in.get();
        byte messageType = (byte) ((h1 & 0x00F0) >> 4);
        return messageType == type ? MessageDecoderResult.OK : MessageDecoderResult.NOT_OK;
    }
    
    /**
     * Return the IoBuffer with string ancoded as MSB, LSB and UTF-8 encoded
     * string content.
     */
    static IoBuffer encodeString(String str) {
        IoBuffer out = IoBuffer.allocate(2).setAutoExpand(true);
        byte[] raw;
        try {
            raw = str.getBytes("UTF-8");
            //NB every Java platform has got UTF-8 encoding by default, so this 
            //exception are never raised.
        } catch (UnsupportedEncodingException ex) {
            Logger.getLogger(ConnectEncoder.class.getName()).log(Level.SEVERE, null, ex);
            return null;
        }
        Utils.writeWord(out, raw.length);
        out.put(raw).flip();
        return out;
    }
    
    
    /**
     * Load a string from the given buffer, reading first the two bytes of len
     * and then the UTF-8 bytes of the string.
     * 
     * @return the decoded string or null if NEED_DATA
     */
    static String decodeString(IoBuffer in) throws UnsupportedEncodingException {
        if (in.remaining() < 2) {
            return null;
        }
        int strLen = Utils.readWord(in);
        if (in.remaining() < strLen) {
            return null;
        }
        byte[] strRaw = new byte[strLen];
        in.get(strRaw);

        return new String(strRaw, "UTF-8");
    }
    
    static byte encodeFlags(AbstractMessage message) {
         byte flags = 0;
        if (message.isDupFlag()) {
            flags |= 0x08;
        }
        
        flags |= ((message.getQos().ordinal() & 0x03) << 1);
        return flags;
    }
}