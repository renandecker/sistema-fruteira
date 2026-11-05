package br.com.fruteira.vendas;
public class CpfCnpjTeste { public static void main(String[] a) {
  boolean ok = Cpf.cnpjValido("11.222.333/0001-81") && !Cpf.cnpjValido("11222333000180") && !Cpf.cnpjValido("11111111111111")
    && "11222333000181".equals(Cpf.normalizarDocumentoOuNulo("11.222.333/0001-81")) && "52998224725".equals(Cpf.normalizarDocumentoOuNulo("529.982.247-25")) && Cpf.normalizarDocumentoOuNulo(" ") == null;
  try { Cpf.normalizarDocumentoOuNulo("11222333000180"); ok = false; } catch (IllegalArgumentException e) { ok &= e.getMessage().equals("CNPJ inválido"); }
  try { Cpf.normalizarDocumentoOuNulo("52998224724"); ok = false; } catch (IllegalArgumentException e) { ok &= e.getMessage().equals("CPF inválido"); }
  System.out.println(ok ? "ok" : "FALHOU"); System.exit(ok ? 0 : 1); } }
