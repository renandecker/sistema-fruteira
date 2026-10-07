package br.com.fruteira.vendas;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;

/** Gera o payload "Pix Copia e Cola" (BR Code / EMV do Banco Central) com o CRC16. Num PSP real, o payload vem pronto do PSP. */
public final class BrCode {
    private BrCode() {}

    private static String tlv(String id, String valor) { return id + String.format("%02d", valor.length()) + valor; }
    private static String ascii(String s, int max) {
        String n = Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFD).replaceAll("\\p{M}", "").replaceAll("[^A-Za-z0-9 ]", "").toUpperCase().trim();
        return n.length() > max ? n.substring(0, max).trim() : n;
    }

    /** Cobrança DINÂMICA (a que o caixa usa): o QR aponta para a URL (location) da cobrança no PSP. */
    public static String dinamico(String location, BigDecimal valor, String nome, String cidade) {
        String conta = tlv("00", "br.gov.bcb.pix") + tlv("25", location.replaceFirst("^https?://", ""));
        return finalizar(tlv("00", "01") + tlv("01", "12") + tlv("26", conta) + tlv("52", "0000") + tlv("53", "986")
            + tlv("54", valor.setScale(2, RoundingMode.HALF_UP).toPlainString()) + tlv("58", "BR")
            + tlv("59", ascii(nome, 25)) + tlv("60", ascii(cidade, 15)) + tlv("62", tlv("05", "***")));
    }
    /** Cobrança ESTÁTICA (chave Pix + valor opcional). */
    public static String estatico(String chave, BigDecimal valor, String nome, String cidade, String txid) {
        String conta = tlv("00", "br.gov.bcb.pix") + tlv("01", chave);
        return finalizar(tlv("00", "01") + tlv("26", conta) + tlv("52", "0000") + tlv("53", "986")
            + (valor == null ? "" : tlv("54", valor.setScale(2, RoundingMode.HALF_UP).toPlainString())) + tlv("58", "BR")
            + tlv("59", ascii(nome, 25)) + tlv("60", ascii(cidade, 15)) + tlv("62", tlv("05", txid == null || txid.isBlank() ? "***" : txid)));
    }
    private static String finalizar(String semCrc) { String p = semCrc + "6304"; return p + crc16(p); }

    /** CRC-16/CCITT-FALSE (polinômio 0x1021, inicial 0xFFFF) — o exigido pelo BR Code. */
    public static String crc16(String s) {
        int crc = 0xFFFF;
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            crc ^= (b & 0xFF) << 8;
            for (int i = 0; i < 8; i++) crc = (crc & 0x8000) != 0 ? ((crc << 1) ^ 0x1021) & 0xFFFF : (crc << 1) & 0xFFFF;
        }
        return String.format("%04X", crc);
    }
}
