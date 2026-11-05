package br.com.fruteira.launcher;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Fruteira — produção só com Java + PostgreSQL (sem Docker, Node, Maven ou nginx).
 *   java -jar fruteira-launcher.jar iniciar [--preparar] | parar | status | backup [--senha X] | restaurar ARQUIVO [--senha X] [--sim]
 * Orquestra: .env (senhas geradas) → bancos no PostgreSQL → chave do token de login → 4 serviços (jars) → servidor web do front.
 */
public final class Main {
    static final String[][] SERVICOS = {{"retaguarda", "8084"}, {"catalogo", "8081"}, {"estoque", "8082"}, {"vendas", "8083"}, {"tef-agent", "8090"}};
    static Path home; static PrintStream out = System.out;
    static final List<Process> filhos = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        System.setErr(new PrintStream(new FileOutputStream(FileDescriptor.err), true, "UTF-8"));
        try { System.exit(executar(args)); }
        catch (IllegalStateException | IllegalArgumentException e) { System.err.println("❌ " + e.getMessage()); System.exit(1); }
    }

    static int executar(String[] args) throws Exception {
        out = new PrintStream(System.out, true, "UTF-8");
        home = localizarHome();
        String cmd = args.length == 0 ? "ajuda" : args[0];
        switch (cmd) {
            case "iniciar": return iniciar(Arrays.asList(args).contains("--preparar"));
            case "parar": return parar();
            case "status": return status();
            case "backup": return Backup.fazer(home, ambiente(false), opcao(args, "--senha"), out);
            case "restaurar": {
                if (args.length < 2) throw new IllegalArgumentException("Uso: restaurar ARQUIVO [--senha SENHA] [--sim]");
                Backup.restaurar(home, ambiente(false), Paths.get(args[1]), opcao(args, "--senha"), Arrays.asList(args).contains("--sim"), out); return 0;
            }
            case "certificado": return certificado(args);
            case "tef": return tef();
            default:
                out.println("Fruteira — produção (só Java + PostgreSQL)\n  iniciar [--preparar]   sobe tudo (--preparar só ajusta o .env)\n  parar                  encerra tudo\n  status                 mostra o que está rodando\n  backup [--senha X]     backup dos bancos + configuração\n  tef                    só o agente TEF deste computador (caixa com maquininha própria)\n  certificado IP [NOME]  cria o HTTPS da rede da loja (necessário para caixas em outros computadores)\n  restaurar ARQ [--senha X] [--sim]");
                return cmd.equals("ajuda") ? 0 : 1;
        }
    }



    /** Variáveis do agente TEF de um CAIXA (computador com a maquininha): só escuta em 127.0.0.1 e aceita só o endereço do front. */
    static Map<String, String> ambienteTef(Map<String, String> e, Path home) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("TEF_PROVIDER", e.getOrDefault("TEF_PROVIDER", "simulador")); m.put("TEF_TOKEN", e.getOrDefault("TEF_TOKEN", ""));
        for (String k : List.of("STONE_CODIGO", "STONE_TERMINAL_SERIAL", "CIELO_EC", "CIELO_TERMINAL", "BB_EC", "BB_TERMINAL", "TEF_SIM_LATENCIA")) if (!e.getOrDefault(k, "").isBlank()) m.put(k, e.get(k));
        m.put("TEF_ORIGENS", e.getOrDefault("TEF_ORIGENS", "").isBlank() ? e.getOrDefault("FRONT_URL", "http://localhost:5173") : e.get("TEF_ORIGENS"));
        m.put("TEF_DADOS", e.getOrDefault("TEF_DADOS", "").isBlank() ? home.resolve("data/tef-agente.json").toString() : e.get("TEF_DADOS"));
        m.put("QUARKUS_HTTP_PORT", e.getOrDefault("TEF_PORTA", "8090")); m.put("QUARKUS_HTTP_HOST", "127.0.0.1"); m.put("QUARKUS_OTEL_SDK_DISABLED", "true");
        return m;
    }
    static int tef() throws Exception {
        Path env = home.resolve(".env"); if (!Files.exists(env)) throw new IllegalStateException(".env não encontrado em " + home + " (copie o .env.example e preencha FRONT_URL, TEF_PROVIDER e TEF_TOKEN)");
        Path jar = home.resolve("servicos/tef-agent/quarkus-run.jar"); if (!Files.exists(jar)) throw new IllegalStateException("servicos/tef-agent/quarkus-run.jar não encontrado (copie a pasta servicos/tef-agent do servidor)");
        Map<String, String> e = new Env(env).mapa(); Files.createDirectories(home.resolve("data")); Files.createDirectories(home.resolve("logs"));
        ProcessBuilder pb = new ProcessBuilder("java", e.getOrDefault("JAVA_OPTS", "-Xmx256m"), "-jar", jar.toString()).directory(jar.getParent().toFile()).inheritIO();
        pb.environment().putAll(ambienteTef(e, home));
        out.println("▶ Agente TEF deste computador em http://127.0.0.1:" + e.getOrDefault("TEF_PORTA", "8090") + " (provedor: " + e.getOrDefault("TEF_PROVIDER", "simulador") + "). Ctrl+C encerra.");
        return pb.start().waitFor();
    }

    /** Certificado HTTPS autoassinado para a rede da loja (os caixas em outros computadores precisam de HTTPS: a balança serial e a criptografia do navegador exigem contexto seguro). */
    static int certificado(String[] a) throws Exception {
        List<String> hosts = new ArrayList<>(); boolean forcar = false; int dias = 825;
        for (int i = 1; i < a.length; i++) { if (a[i].equals("--forcar")) forcar = true; else if (a[i].equals("--dias") && i + 1 < a.length) dias = Integer.parseInt(a[++i]); else hosts.add(a[i].trim()); }
        if (hosts.isEmpty()) throw new IllegalArgumentException("Uso: certificado IP-OU-NOME-DO-SERVIDOR [outro-nome-ou-ip ...] [--dias 825] [--forcar]\n  ex.: certificado 192.168.0.10 servidor-loja");
        for (String h : hosts) if (!h.matches("[A-Za-z0-9.-]+")) throw new IllegalArgumentException("Nome/IP inválido: " + h);
        Path envArq = home.resolve(".env"); if (!Files.exists(envArq)) throw new IllegalStateException(".env não encontrado: rode 'iniciar' uma vez (ele cria o .env) e depois este comando");
        Path dir = home.resolve("certs"), p12 = dir.resolve("fruteira.p12"), cer = dir.resolve("fruteira.cer");
        if (Files.exists(p12) && !forcar) throw new IllegalStateException(p12 + " já existe (use --forcar para gerar outro; os caixas precisarão confiar no novo .cer)");
        Files.createDirectories(dir); Files.deleteIfExists(p12); Files.deleteIfExists(cer);
        String kt = Paths.get(System.getProperty("java.home"), "bin", Banco.windows() ? "keytool.exe" : "keytool").toString();
        String senha = Env.senha(24); StringBuilder san = new StringBuilder("san=dns:localhost,ip:127.0.0.1");
        for (String h : hosts) san.append(h.matches("\\d{1,3}(\\.\\d{1,3}){3}") ? ",ip:" : ",dns:").append(h);
        exec(List.of(kt, "-genkeypair", "-alias", "fruteira", "-keyalg", "RSA", "-keysize", "2048", "-validity", String.valueOf(dias), "-storetype", "PKCS12", "-keystore", p12.toString(),
            "-storepass", senha, "-keypass", senha, "-dname", "CN=" + hosts.get(0) + ", O=Fruteira", "-ext", san.toString(), "-ext", "eku=serverAuth"));
        exec(List.of(kt, "-exportcert", "-rfc", "-alias", "fruteira", "-keystore", p12.toString(), "-storepass", senha, "-file", cer.toString()));
        Env e = new Env(envArq); String h = hosts.get(0);
        e.set("WEB_TLS_P12", p12.toString().replace('\\', '/')); e.set("WEB_TLS_SENHA", senha);
        e.set("FRONT_URL", "https://" + h + ":" + e.getOrDefault("WEB_PORT", "5173"));
        e.salvar();
        out.println("✔ Certificado criado: " + p12 + " (válido por " + dias + " dias, nomes: localhost, 127.0.0.1, " + String.join(", ", hosts) + ")");
        out.println("✔ .env atualizado: WEB_TLS_*, FRONT_URL=https://" + h + ":5173");
        out.println("\nEm CADA computador de caixa, faça o navegador confiar no certificado (uma vez):");
        out.println("  1) copie certs/fruteira.cer do servidor para o caixa");
        out.println("  2) Windows (como administrador):  certutil -addstore -f Root fruteira.cer");
        out.println("     Linux: sudo cp fruteira.cer /usr/local/share/ca-certificates/fruteira.crt && sudo update-ca-certificates");
        out.println("  3) feche e abra o Chrome/Edge e acesse https://" + h + ":5173");
        out.println("Reinicie com: parar && iniciar");
        return 0;
    }
    static void exec(List<String> cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start(); String saida = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (p.waitFor() != 0) throw new IllegalStateException("keytool falhou: " + saida.strip());
    }

    static String opcao(String[] a, String nome) { for (int i = 0; i < a.length - 1; i++) if (a[i].equals(nome)) return a[i + 1]; return null; }

    static Path localizarHome() throws Exception {
        String h = System.getenv("FRUTEIRA_HOME"); if (h != null && !h.isBlank()) return Paths.get(h).toAbsolutePath().normalize();
        Path jar = Paths.get(Main.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        return (Files.isRegularFile(jar) ? jar.getParent() : Paths.get("").toAbsolutePath()).toAbsolutePath().normalize();
    }

    // ------------------------------------------------------------------ ambiente (.env)
    static Env preparaEnv() throws IOException {
        Path arq = home.resolve(".env");
        if (!Files.exists(arq)) {
            Path ex = home.resolve(".env.example");
            if (!Files.exists(ex)) throw new IllegalStateException(".env não encontrado em " + home + " (copie o .env.example)");
            Files.copy(ex, arq); out.println("ℹ .env criado a partir do .env.example");
        }
        Env env = new Env(arq); env.set("APP_ENV", "producao");
        for (String k : env.gerarSenhas("DB_PASS", "TEF_TOKEN", "JWT_SEGREDO", "ADMIN_SENHA_INICIAL")) {
            out.println("🔑 " + k + " estava vazio/de exemplo: gerada uma senha aleatória e gravada no .env (guarde o arquivo!)");
            if (k.equals("ADMIN_SENHA_INICIAL")) out.println("   👤 Primeiro acesso: usuário 'gerente', senha '" + env.get(k) + "' (você cria a sua nova senha ao entrar).");
        }
        env.salvar();
        return env;
    }

    /** Variáveis dos processos filhos: ambiente do sistema + .env (o .env vence) + padrões. */
    static Map<String, String> ambiente(boolean gerar) throws IOException {
        Env env = gerar ? preparaEnv() : new Env(home.resolve(".env"));
        Map<String, String> m = new LinkedHashMap<>(System.getenv()); m.putAll(env.mapa());
        m.put("FRUTEIRA_HOME", home.toString());
        m.putIfAbsent("FRONT_URL", "http://localhost:5173");
        if (m.getOrDefault("DB_URL", "").isBlank()) m.put("DB_URL", "jdbc:postgresql://localhost:5432/fruteira");
        if (m.getOrDefault("DB_USER", "").isBlank()) m.put("DB_USER", "fruteira");
        if (m.getOrDefault("FISCAL_CERT_PATH", "").isBlank() && Files.isRegularFile(home.resolve("certificado.pfx"))) m.put("FISCAL_CERT_PATH", home.resolve("certificado.pfx").toString());
        else if (!m.getOrDefault("FISCAL_CERT_PATH", "").isBlank() && !Paths.get(m.get("FISCAL_CERT_PATH")).isAbsolute() && Files.isRegularFile(home.resolve(m.get("FISCAL_CERT_PATH")))) m.put("FISCAL_CERT_PATH", home.resolve(m.get("FISCAL_CERT_PATH")).toString());
        mapearOtel(m);
        return m;
    }

    static boolean ligado(String v) { return v != null && (v.equalsIgnoreCase("true") || v.equals("1") || v.equalsIgnoreCase("sim")); }
    /** OTEL_* do .env → QUARKUS_OTEL_* dos serviços (só com OTEL_ENABLED=true; senão o SDK fica desligado). */
    static void mapearOtel(Map<String, String> m) {
        if (!ligado(m.get("OTEL_ENABLED"))) { m.put("QUARKUS_OTEL_SDK_DISABLED", "true"); return; }
        m.put("QUARKUS_OTEL_SDK_DISABLED", "false");
        // Atalho Dynatrace: DYNATRACE_OTLP_URL + DYNATRACE_TOKEN (+ DYNATRACE_AUTH=Bearer|Api-Token) preenchem endpoint, cabeçalho e protocolo (o Dynatrace não aceita gRPC).
        String dtUrl = m.getOrDefault("DYNATRACE_OTLP_URL", "").trim(), dtTok = m.getOrDefault("DYNATRACE_TOKEN", "").trim();
        if (!dtUrl.isEmpty() || !dtTok.isEmpty()) {
            if (dtUrl.isEmpty() || dtTok.isEmpty()) throw new IllegalStateException("Dynatrace: preencha DYNATRACE_OTLP_URL e DYNATRACE_TOKEN (os dois)");
            if (!dtUrl.startsWith("https://")) throw new IllegalStateException("DYNATRACE_OTLP_URL deve começar com https://");
            if (dtTok.matches(".*[\\s,\"].*")) throw new IllegalStateException("DYNATRACE_TOKEN não pode ter espaço, vírgula ou aspas");
            String esq = m.getOrDefault("DYNATRACE_AUTH", "Bearer").trim();
            if (!esq.equals("Bearer") && !esq.equals("Api-Token")) throw new IllegalStateException("DYNATRACE_AUTH deve ser Bearer (token de plataforma) ou Api-Token (token clássico)");
            m.put("OTEL_EXPORTER_OTLP_ENDPOINT", dtUrl.replaceAll("/+$", "").replaceAll("/v1/(traces|metrics|logs)$", ""));
            m.put("OTEL_EXPORTER_OTLP_HEADERS", "Authorization=" + esq + " " + dtTok);
            m.put("OTEL_EXPORTER_OTLP_PROTOCOL", "http/protobuf");
        }
        String ep = m.getOrDefault("OTEL_EXPORTER_OTLP_ENDPOINT", "").trim(); if (!ep.isEmpty()) m.put("QUARKUS_OTEL_EXPORTER_OTLP_ENDPOINT", ep);
        String pr = m.getOrDefault("OTEL_EXPORTER_OTLP_PROTOCOL", "").trim(); if (!pr.isEmpty()) { m.put("QUARKUS_OTEL_EXPORTER_OTLP_PROTOCOL", pr); m.put("QUARKUS_OTEL_EXPORTER_OTLP_TRACES_PROTOCOL", pr); }
        String hd = m.getOrDefault("OTEL_EXPORTER_OTLP_HEADERS", "").trim(); if (!hd.isEmpty()) m.put("QUARKUS_OTEL_EXPORTER_OTLP_HEADERS", hd);
        String am = m.getOrDefault("OTEL_TRACES_SAMPLER_ARG", "").trim(); if (!am.isEmpty()) m.put("QUARKUS_OTEL_TRACES_SAMPLER_ARG", am);
    }

    // ------------------------------------------------------------------ iniciar
    static int iniciar(boolean soPreparar) throws Exception {
        if (Runtime.version().feature() < 21) throw new IllegalStateException("Java 21 ou mais novo é necessário (encontrado: " + Runtime.version() + "). Instale o JRE/JDK 21.");
        Map<String, String> e = ambiente(true);
        if (soPreparar) { out.println("✔ Preparação concluída (--preparar): nada foi iniciado."); return 0; }
        if (!vivos().isEmpty()) throw new IllegalStateException("A Fruteira já está rodando (veja 'status'). Use 'parar' antes de iniciar de novo.");
        for (String k : List.of("DB_PASS", "JWT_SEGREDO")) if (Env.exemplo(e.getOrDefault(k, ""))) throw new IllegalStateException("Defina um valor seguro para " + k + " no .env");
        if (e.get("JWT_SEGREDO").length() < 32) throw new IllegalStateException("JWT_SEGREDO deve ter pelo menos 32 caracteres (apague a linha no .env para gerar outro)");
        validarFiscal(e); validarPix(e);
        if (ligado(e.get("OTEL_ENABLED")) && e.getOrDefault("OTEL_EXPORTER_OTLP_ENDPOINT", "").isBlank()) throw new IllegalStateException("OTEL_ENABLED=true exige OTEL_EXPORTER_OTLP_ENDPOINT no .env (URL do coletor OTLP)");
        Banco.alvo(e.get("DB_URL"));
        for (String[] s : SERVICOS) if (aberta(Integer.parseInt(s[1]))) throw new IllegalStateException("A porta " + s[1] + " (" + s[0] + ") já está em uso por outro programa. Se for a Fruteira, rode 'parar'.");
        int portaWeb = Integer.parseInt(e.getOrDefault("WEB_PORT", "5173"));
        if (aberta(portaWeb)) throw new IllegalStateException("A porta " + portaWeb + " já está em uso por outro programa.");
        for (String s : List.of("servicos/retaguarda/quarkus-run.jar", "web/index.html"))
            if (!Files.exists(home.resolve(s))) throw new IllegalStateException("Arquivo do pacote ausente: " + s + " (gere o pacote de novo com gerar-producao)");

        Files.createDirectories(home.resolve("logs")); Files.createDirectories(home.resolve("run"));
        Banco.preparar(e, out);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> { encerrar(true); }));
        Files.writeString(home.resolve("run/pids.txt"), "launcher=" + ProcessHandle.current().pid() + "\n");

        // chave do token de login: o retaguarda assina (JWT_SEGREDO) e os 4 serviços verificam com o mesmo segredo, lido deste arquivo JWK
        Path jwk = home.resolve("run/jwt.jwk");
        Files.writeString(jwk, jwkDe(e.get("JWT_SEGREDO")), StandardCharsets.UTF_8);
        try { Files.setPosixFilePermissions(jwk, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")); } catch (Exception ignorado) { /* Windows */ }
        e.put("JWT_VERIFY_JWK", jwk.toString().replace('\\', '/'));

        String tunel = ngrok(e);
        if (tunel != null) { e.put("NGROK_URL", tunel); e.put("NGROK_API", "http://127.0.0.1:4040/api/tunnels"); }

        // serviços Java (retaguarda PRIMEIRO: dono do Flyway)
        for (String[] s : SERVICOS) {
            Path dir = home.resolve("servicos").resolve(s[0]);
            if (!Files.exists(dir.resolve("quarkus-run.jar"))) throw new IllegalStateException("servicos/" + s[0] + "/quarkus-run.jar não encontrado (gere o pacote de novo)");
            out.println("Iniciando " + s[0] + "...");
            List<String> cmd = new ArrayList<>(List.of("java")); cmd.addAll(Arrays.asList(e.getOrDefault("JAVA_OPTS", "-Xmx384m").trim().split("\\s+"))); cmd.addAll(List.of("-jar", "quarkus-run.jar"));
            Map<String, String> es = new LinkedHashMap<>(e); es.put("QUARKUS_OTEL_RESOURCE_ATTRIBUTES", "service.namespace=fruteira,deployment.environment.name=" + e.getOrDefault("OBS_AMBIENTE", "producao") + ",service.version=" + e.getOrDefault("OBS_VERSAO", "1.0.0"));
            Process p = iniciarProcesso(s[0], cmd, dir, es);
            esperar(s[0], Integer.parseInt(s[1]), p, 240);
        }

        // servidor web (front + proxy das APIs)
        Map<String, Integer> api = Map.of("catalogo", 8081, "estoque", 8082, "vendas", 8083, "retaguarda", 8084);
        new ServidorWeb(home.resolve("web"), api, ServidorWeb.configJs(e))
            .iniciar(portaWeb, e.getOrDefault("WEB_TLS_P12", ""), e.getOrDefault("WEB_TLS_SENHA", ""));
        out.println("✔ web no ar (porta " + portaWeb + ")");
        out.println();
        out.println("🍎 Fruteira no ar: " + e.get("FRONT_URL"));
        out.println("🔐 Login próprio: entre como 'gerente' (senha ADMIN_SENHA_INICIAL do .env, vale só enquanto não existir gerente) e crie os usuários dos caixas em Usuários e perfis.");
        out.println("Logs em ./logs — Ctrl+C (ou 'parar') encerra tudo.");
        if (Banco.windows() && !"false".equalsIgnoreCase(e.getOrDefault("ABRIR_NAVEGADOR", "true"))) try { new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", e.get("FRONT_URL")).start(); } catch (IOException ignorado) { /* sem navegador */ }
        // fica supervisionando: se um serviço cair, avisa
        while (true) {
            Thread.sleep(5000);
            for (Process p : filhos) if (!p.isAlive() && p.exitValue() != 0 && !encerrando) { out.println("⚠ Um serviço encerrou (código " + p.exitValue() + "). Veja ./logs."); filhos.remove(p); break; }
        }
    }

    static volatile boolean encerrando = false;

    static void validarFiscal(Map<String, String> e) {
        if (!"sefaz".equalsIgnoreCase(e.getOrDefault("FISCAL_PROVIDER", "simulador"))) { out.println("ℹ NFC-e/NF-e em modo SIMULADOR (FISCAL_PROVIDER=simulador): sem valor fiscal. Para emitir de verdade: FISCAL_PROVIDER=sefaz (ver README)."); return; }
        List<String> falta = new ArrayList<>();
        for (String k : List.of("FISCAL_CNPJ", "FISCAL_RAZAO", "FISCAL_IE", "FISCAL_LOGRADOURO", "FISCAL_BAIRRO", "FISCAL_CMUN", "FISCAL_MUNICIPIO", "FISCAL_CEP", "FISCAL_CSC_ID", "FISCAL_CSC", "FISCAL_CERT_SENHA")) if (e.getOrDefault(k, "").isBlank()) falta.add(k);
        if (!falta.isEmpty()) throw new IllegalStateException("FISCAL_PROVIDER=sefaz exige no .env: " + String.join(" ", falta));
        String c = e.getOrDefault("FISCAL_CERT_PATH", "");
        if (c.isBlank() || !Files.isReadable(Paths.get(c))) throw new IllegalStateException("Certificado A1 não encontrado: coloque o arquivo certificado.pfx na pasta " + home + " ou aponte FISCAL_CERT_PATH" + (c.isBlank() ? "" : " (atual: " + c + ")"));
        out.println("1".equals(e.getOrDefault("FISCAL_AMBIENTE", "2")) ? "⚠ Notas em PRODUÇÃO (FISCAL_AMBIENTE=1): têm valor fiscal." : "ℹ Notas em HOMOLOGAÇÃO (FISCAL_AMBIENTE=2): sem valor fiscal.");
    }

    /** JWK simétrico (HS256) com o segredo: é o que os serviços usam para verificar o token emitido pelo retaguarda. */
    static String jwkDe(String segredo) {
        String k = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(segredo.getBytes(StandardCharsets.UTF_8));
        return "{\"kty\":\"oct\",\"alg\":\"HS256\",\"use\":\"sig\",\"k\":\"" + k + "\"}\n";
    }

    static void validarPix(Map<String, String> e) {
        String prov = e.getOrDefault("PIX_PROVIDER", "simulador").toUpperCase().replace(' ', '_').replace('-', '_');
        if (!List.of("SIMULADOR", "EFI", "MERCADO_PAGO", "ASAAS", "ITAU", "BRADESCO", "SICOOB", "SICREDI").contains(prov))
            throw new IllegalStateException("PIX_PROVIDER inválido ('" + e.get("PIX_PROVIDER") + "'). Use: SIMULADOR, EFI, MERCADO_PAGO, ASAAS, ITAU, BRADESCO, SICOOB ou SICREDI");
        if (prov.equals("SIMULADOR")) { out.println("ℹ Pix em modo SIMULADOR (PIX_PROVIDER=simulador): não é Pix real."); return; }
        List<String> req = switch (prov) {
            case "MERCADO_PAGO" -> List.of("PIX_MERCADO_PAGO_ACCESS_TOKEN", "PIX_MERCADO_PAGO_EMAIL_PAGADOR");
            case "ASAAS" -> List.of("PIX_ASAAS_API_KEY", "PIX_ASAAS_CLIENTE_ID");
            default -> List.of("PIX_" + prov + "_CLIENT_ID", "PIX_" + prov + "_CLIENT_SECRET", "PIX_" + prov + "_CERTIFICADO");
        };
        List<String> falta = new ArrayList<>(); for (String k : req) if (e.getOrDefault(k, "").isBlank()) falta.add(k);
        if (!prov.equals("MERCADO_PAGO") && !prov.equals("ASAAS") && e.getOrDefault("PIX_CHAVE", "").isBlank() && e.getOrDefault("PIX_" + prov + "_CHAVE", "").isBlank()) falta.add("PIX_CHAVE");
        if (prov.equals("MERCADO_PAGO") && e.getOrDefault("FRUTEIRA_PIX_WEBHOOK_URL", "").isBlank() && e.getOrDefault("NGROK_URL", "").isBlank() && !"true".equals(e.getOrDefault("USAR_NGROK", "false"))) falta.add("FRUTEIRA_PIX_WEBHOOK_URL (ou USAR_NGROK=true/NGROK_URL)");
        if (!falta.isEmpty()) throw new IllegalStateException("PIX_PROVIDER=" + prov + " exige no .env: " + String.join(" ", falta));
        String cert = e.getOrDefault("PIX_" + prov + "_CERTIFICADO", "");
        if (!cert.isBlank() && !Files.isReadable(home.resolve(cert))) throw new IllegalStateException("Certificado do Pix não encontrado/legível: " + cert);
        if (e.getOrDefault("FRUTEIRA_PIX_WEBHOOK_SEGREDO", "").isBlank()) throw new IllegalStateException("Pix com provedor real exige FRUTEIRA_PIX_WEBHOOK_SEGREDO no .env (o webhook é público)");
    }

    /** Túnel Ngrok opcional (USAR_NGROK=true) só para o webhook do Pix: precisa do programa ngrok instalado. Devolve a URL pública ou null. */
    static String ngrok(Map<String, String> e) throws Exception {
        if (!"true".equals(e.getOrDefault("USAR_NGROK", "false"))) return null;
        List<String> cmd = new ArrayList<>(List.of("ngrok", "http", "8083", "--log=stdout"));
        if (!e.getOrDefault("NGROK_DOMINIO", "").isBlank()) cmd.add("--url=" + e.get("NGROK_DOMINIO"));
        if (!e.getOrDefault("NGROK_ARGS", "").isBlank()) cmd.addAll(Arrays.asList(e.get("NGROK_ARGS").trim().split("\\s+")));
        try { iniciarProcesso("ngrok", cmd, home, e); } catch (IOException ex) { throw new IllegalStateException("USAR_NGROK=true, mas o 'ngrok' não está no PATH (https://ngrok.com/download e depois: ngrok config add-authtoken SEU_TOKEN)"); }
        var http = java.net.http.HttpClient.newHttpClient();
        for (int i = 0; i < 20; i++) {
            try {
                String t = http.send(java.net.http.HttpRequest.newBuilder(URI.create("http://127.0.0.1:4040/api/tunnels")).build(), java.net.http.HttpResponse.BodyHandlers.ofString()).body();
                var m = java.util.regex.Pattern.compile("\"public_url\": *\"(https://[^\"]*)").matcher(t);
                if (m.find()) { out.println("🌐 Ngrok no ar: " + m.group(1)); return m.group(1); }
            } catch (Exception ignorado) { /* ainda subindo */ }
            Thread.sleep(1000);
        }
        out.println("⚠ O Ngrok não informou o endereço do túnel em 20 s — veja logs/ngrok.log. O Pix seguirá sem webhook (consulta a cada 10 s).");
        return null;
    }

    // ------------------------------------------------------------------ processos
    static Process iniciarProcesso(String nome, List<String> cmd, Path dir, Map<String, String> env) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(home.resolve("logs").resolve(nome + ".log").toFile()));
        pb.environment().clear(); pb.environment().putAll(env);
        Process p = pb.start(); filhos.add(p);
        Files.writeString(home.resolve("run/pids.txt"), nome + "=" + p.pid() + "\n", StandardOpenOption.APPEND);
        return p;
    }

    static void esperar(String nome, int porta, Process p, int segundos) throws Exception {
        for (int i = 0; i < segundos / 2; i++) {
            if (aberta(porta)) { out.println("✔ " + nome + " no ar (porta " + porta + ")"); return; }
            if (!p.isAlive()) throw new IllegalStateException(nome + " encerrou ao iniciar (código " + p.exitValue() + "). Últimas linhas de logs/" + nome + ".log:\n" + cauda(home.resolve("logs").resolve(nome + ".log"), 15));
            Thread.sleep(2000);
        }
        throw new IllegalStateException(nome + " não respondeu na porta " + porta + " em " + segundos + " s. Veja logs/" + nome + ".log:\n" + cauda(home.resolve("logs").resolve(nome + ".log"), 15));
    }

    static String cauda(Path f, int n) {
        try { List<String> l = Files.readAllLines(f); return String.join("\n", l.subList(Math.max(0, l.size() - n), l.size())); } catch (Exception e) { return "(sem log)"; }
    }

    static boolean aberta(int porta) {
        try (Socket s = new Socket()) { s.connect(new InetSocketAddress("127.0.0.1", porta), 400); return true; } catch (IOException e) { return false; }
    }

    // ------------------------------------------------------------------ parar / status
    static Map<String, Long> pids() throws IOException {
        Map<String, Long> m = new LinkedHashMap<>(); Path f = home.resolve("run/pids.txt");
        if (Files.exists(f)) for (String l : Files.readAllLines(f)) { int i = l.indexOf('='); if (i > 0) try { m.put(l.substring(0, i), Long.parseLong(l.substring(i + 1).trim())); } catch (NumberFormatException ignorado) { /* linha ruim */ } }
        return m;
    }
    static Map<String, Long> vivos() throws IOException {
        Map<String, Long> v = new LinkedHashMap<>(); long eu = ProcessHandle.current().pid();
        for (var e : pids().entrySet()) if (e.getValue() != eu && ProcessHandle.of(e.getValue()).map(ProcessHandle::isAlive).orElse(false)) v.put(e.getKey(), e.getValue());
        return v;
    }

    /** Encerra os filhos (e os netos: o cmd do Windows cria outro processo). */
    static void encerrar(boolean daJvm) {
        encerrando = true;
        try {
            List<ProcessHandle> alvos = new ArrayList<>(); long eu = ProcessHandle.current().pid();
            List<Map.Entry<String, Long>> l = new ArrayList<>(pids().entrySet()); Collections.reverse(l);
            for (var e : l) if (e.getValue() != eu) ProcessHandle.of(e.getValue()).ifPresent(h -> { h.descendants().forEach(alvos::add); alvos.add(h); });
            alvos.forEach(ProcessHandle::destroy);
            long fim = System.currentTimeMillis() + 20_000;
            while (System.currentTimeMillis() < fim && alvos.stream().anyMatch(ProcessHandle::isAlive)) Thread.sleep(300);
            alvos.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
            if (!daJvm) { Path f = home.resolve("run/pids.txt"); Long lan = pids().get("launcher"); if (lan != null && lan != eu) ProcessHandle.of(lan).ifPresent(ProcessHandle::destroy); Files.deleteIfExists(f); }
        } catch (Exception ignorado) { /* encerrando */ }
    }

    static int parar() throws Exception {
        var v = vivos();
        if (v.isEmpty()) { out.println("Nada rodando."); Files.deleteIfExists(home.resolve("run/pids.txt")); return 0; }
        out.println("Encerrando: " + String.join(", ", v.keySet()) + "...");
        encerrar(false);
        out.println("✔ Serviços encerrados (o PostgreSQL continua rodando, como serviço da máquina).");
        return 0;
    }

    static int status() throws Exception {
        var v = vivos();
        for (String[] s : SERVICOS) out.println(String.format("%-11s %s", s[0], aberta(Integer.parseInt(s[1])) ? "no ar (porta " + s[1] + ")" : "parado"));
        Map<String, String> e = ambiente(false);
        out.println(String.format("%-11s %s", "web", aberta(Integer.parseInt(e.getOrDefault("WEB_PORT", "5173"))) ? "no ar" : "parado"));
        return v.isEmpty() ? 1 : 0;
    }
}
