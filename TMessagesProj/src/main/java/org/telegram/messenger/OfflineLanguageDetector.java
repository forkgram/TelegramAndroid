package org.telegram.messenger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

public class OfflineLanguageDetector {

    private static final int MIN_LETTERS = 5;
    private static final float MIN_SCRIPT_SHARE = 0.6f;
    private static final double MIN_SCORE = 2.0;
    private static final double MIN_MARGIN = 1.0;

    private static final int CODE = 0, WORDS = 1, LETTERS = 2;

    private static final Pattern NOISE = Pattern.compile(
        "(https?://\\S+)|(www\\.\\S+)|(\\S+@\\S+\\.\\S+)|([@#$][\\p{L}\\p{N}_]+)"
    );

    private static final int OTHER = 0, LATIN = 1, CYRILLIC = 2, GREEK = 3, ARABIC = 4, HEBREW = 5,
        ARMENIAN = 6, GEORGIAN = 7, THAI = 8, LAO = 9, KHMER = 10, MYANMAR = 11, TIBETAN = 12,
        DEVANAGARI = 13, BENGALI = 14, GURMUKHI = 15, GUJARATI = 16, ORIYA = 17, TAMIL = 18,
        TELUGU = 19, KANNADA = 20, MALAYALAM = 21, SINHALA = 22, ETHIOPIC = 23, THAANA = 24,
        HANGUL = 25, HIRAGANA = 26, KATAKANA = 27, HAN = 28, SCRIPTS = 29;

    private static final String[] SINGLE_LANGUAGE_SCRIPTS = new String[SCRIPTS];
    static {
        SINGLE_LANGUAGE_SCRIPTS[GREEK] = "el";
        SINGLE_LANGUAGE_SCRIPTS[HEBREW] = "he";
        SINGLE_LANGUAGE_SCRIPTS[ARMENIAN] = "hy";
        SINGLE_LANGUAGE_SCRIPTS[GEORGIAN] = "ka";
        SINGLE_LANGUAGE_SCRIPTS[THAI] = "th";
        SINGLE_LANGUAGE_SCRIPTS[LAO] = "lo";
        SINGLE_LANGUAGE_SCRIPTS[KHMER] = "km";
        SINGLE_LANGUAGE_SCRIPTS[MYANMAR] = "my";
        SINGLE_LANGUAGE_SCRIPTS[TIBETAN] = "bo";
        SINGLE_LANGUAGE_SCRIPTS[BENGALI] = "bn";
        SINGLE_LANGUAGE_SCRIPTS[GURMUKHI] = "pa";
        SINGLE_LANGUAGE_SCRIPTS[GUJARATI] = "gu";
        SINGLE_LANGUAGE_SCRIPTS[ORIYA] = "or";
        SINGLE_LANGUAGE_SCRIPTS[TAMIL] = "ta";
        SINGLE_LANGUAGE_SCRIPTS[TELUGU] = "te";
        SINGLE_LANGUAGE_SCRIPTS[KANNADA] = "kn";
        SINGLE_LANGUAGE_SCRIPTS[MALAYALAM] = "ml";
        SINGLE_LANGUAGE_SCRIPTS[SINHALA] = "si";
        SINGLE_LANGUAGE_SCRIPTS[ETHIOPIC] = "am";
        SINGLE_LANGUAGE_SCRIPTS[THAANA] = "dv";
        SINGLE_LANGUAGE_SCRIPTS[HANGUL] = "ko";
    }

    private static final String[][] LATIN_CANDIDATES = {
        { "en", "the and you that for with this have not but was are what from they your just like about would there when who will its been which more some than then them being", "" },
        { "es", "que los las por una para pero como muy está esto más todo bien hola gracias porque cuando también sobre nada nunca ella eres estoy tengo hacer", "ñ¡¿" },
        { "pt", "que não uma para com você mais mas isso como tem muito obrigado então aqui bem também está fazer sempre ainda dele quem tudo agora", "ãõç" },
        { "fr", "les des est pas une vous nous pour qui dans sur avec mais tout bien merci bonjour être cette elle sont fait comme aussi plus alors très", "çœàèùêâîô" },
        { "de", "der die das und ist nicht ein eine ich sie wir mit für auf den dem aber auch noch schon wie was danke hallo sehr oder haben sein mehr immer", "äöüß" },
        { "it", "che non per una sono con come più questo gli anche molto ciao grazie cosa quando perché fare tutto adesso sempre loro essere questa", "àèìòù" },
        { "nl", "het een niet van dat ook aan die hij hoe heel dank hallo naar deze zijn maar voor met omdat weer nog wel jij mijn", "" },
        { "pl", "nie jest się to że jak ale czy tak juz tylko bardzo dziękuję cześć jeszcze mnie ciebie który dobrze teraz coś przez może wszystko", "łąężźćńś" },
        { "tr", "bir için ile bu var yok çok ama daha nasıl merhaba teşekkür değil şey sonra kadar gibi olarak beni seni evet hayır bugün", "ığş" },
        { "ro", "este nu cu pentru care mai și din sunt dar foarte mulțumesc bună acum ceva cum când tot ele acest doar despre", "ășțăî" },
        { "cs", "je na se to že ne ale jak co jsem jsi jsme jste není také ještě jenom děkuji ahoj dobře teď tady který proto", "řěůňť" },
        { "sk", "je na sa to že ale ako čo som sme ste nie aj ešte len ďakujem ahoj dobre teraz tu ktorý preto veľmi", "ĺŕôľŧ" },
        { "hu", "az és hogy nem van egy csak már mit még nagyon köszönöm szia jól most itt vagy lesz kell vagyok mert amit", "őű" },
        { "fi", "ja on ei se että kuin mutta niin hän minä olen tämä sitten vain kyllä kiitos moi nyt siis mitä missä koska", "äö" },
        { "sv", "och att är det som inte för med han hon men här nu tack hej mycket bara vad när hur något jag har den", "åäö" },
        { "da", "og er ikke det en til på der har jeg hvad også noget mig dig være tak hej meget kun hvor hvordan denne", "æøå" },
        { "no", "og er ikke det en til på som har jeg hva noe meg deg være takk hei mye bare hvor hvordan denne kan", "æøå" },
        { "hr", "je ne da se što ali kao ovo jer sam su smo ste nije hvala bok sada ovdje koji vrlo puno kada", "čćžšđ" },
        { "sl", "je ne in se kaj ampak samo tudi zdaj sem si smo ste hvala živjo tukaj kateri zelo kdaj lahko bom", "čžš" },
        { "lt", "yra ne kad bet kaip tai aš tu mes jūs labai ačiū labas dabar čia kuris kada tik dar viskas", "ėįųū" },
        { "lv", "ir nav ka bet kā tas es tu mēs jūs ļoti paldies sveiki tagad šeit kurš kad tikai vēl viss", "āēģķļņ" },
        { "et", "ja on ei see kui aga ma sa me te väga aitäh tere nüüd siin kes millal ainult veel kõik mis", "õäöü" },
        { "sq", "dhe për është nuk një kjo të me si nga por shumë faleminderit përshëndetje tani këtu kur vetëm çfarë", "ëç" },
        { "az", "və bir bu üçün ilə var yox mən sən biz siz çox təşəkkür salam indi burada nə zaman ancaq hər", "əğışç" },
        { "uz", "va bu bir uchun bilan men sen biz siz juda rahmat salom hozir bura nima qachon faqat yoq bor", "ʻʼ" },
        { "id", "yang dan itu tidak ini untuk dengan saya ada adalah akan dari bisa sudah kamu banget aja nggak kalau juga halo kabar terima kasih banyak semua apa", "" },
        { "ms", "boleh awak macam sangat kena tak nak jangan sini situ kenapa bila siapa begitu", "" },
        { "tl", "ang mga sa na ay ko ito hindi yung naman ako ikaw kayo talaga lang pero kasi ngayon salamat kumusta", "" },
        { "ca", "que els les amb per això també molt aquest sóc som sou molta gràcies hola ara aquí quan només què", "·ïàèòç" },
        { "af", "die en is nie van dat het met vir ek jy ons hulle baie dankie hallo nou hier wat wanneer net", "êôû" },
        { "sw", "na ya wa kwa ni hii kuwa katika sana habari asante karibu sasa hapa nini lini tu kila watu", "" }
    };

    private static final String[][] CYRILLIC_CANDIDATES = {
        { "ru", "что это как для есть был была были очень если меня тебя они мы все всё ещё или тоже только можно нужно где когда почему спасибо привет хорошо сейчас просто время", "" },
        { "bg", "това със съм ще който защото много добре така само може няма имам благодаря здравей сега тук там какво кога защо съм беше техен", "" },
        { "uk", "що це як для є був була були дуже якщо мене тебе вони ми все ще або теж тільки можна треба де коли чому дякую привіт добре зараз просто", "" },
        { "be", "што гэта як для ёсць быў была былі вельмі калі мяне цябе яны мы усё яшчэ або таксама толькі можна трэба дзе чаму дзякуй прывітанне добра зараз проста", "" }
    };

    private static final HashMap<String, int[]> LATIN_INDEX = buildIndex(LATIN_CANDIDATES);
    private static final HashMap<String, int[]> CYRILLIC_INDEX = buildIndex(CYRILLIC_CANDIDATES);

    public static String detect(String text) {
        if (text == null || text.length() == 0) {
            return null;
        }
        final String clean = NOISE.matcher(text).replaceAll(" ").toLowerCase(Locale.ROOT);

        final int[] counts = new int[SCRIPTS];
        int letters = 0;
        for (int i = 0; i < clean.length(); ) {
            final int cp = clean.codePointAt(i);
            i += Character.charCount(cp);
            if (!Character.isLetter(cp)) {
                continue;
            }
            letters++;
            counts[scriptOf(cp)]++;
        }
        if (letters < MIN_LETTERS) {
            return null;
        }

        final boolean hasKana = counts[HIRAGANA] + counts[KATAKANA] > 0;
        if (hasKana && counts[HAN] + counts[HIRAGANA] + counts[KATAKANA] >= letters * MIN_SCRIPT_SHARE) {
            return "ja";
        }

        int script = OTHER, best = 0;
        for (int i = 1; i < SCRIPTS; i++) {
            if (counts[i] > best) {
                best = counts[i];
                script = i;
            }
        }
        if (best < letters * MIN_SCRIPT_SHARE) {
            return null;
        }

        switch (script) {
            case HAN: return "zh";
            case HIRAGANA:
            case KATAKANA: return "ja";
            case LATIN: return latin(clean);
            case CYRILLIC: return cyrillic(clean);
            case ARABIC: return arabic(clean);
            case DEVANAGARI: return devanagari(clean);
            default: return SINGLE_LANGUAGE_SCRIPTS[script];
        }
    }

    private static int scriptOf(int cp) {
        if (cp < 0x0250) return LATIN;
        if (cp >= 0x1E00 && cp <= 0x1EFF) return LATIN;
        if (cp >= 0x0370 && cp <= 0x03FF || cp >= 0x1F00 && cp <= 0x1FFF) return GREEK;
        if (cp >= 0x0400 && cp <= 0x052F || cp >= 0xA640 && cp <= 0xA69F) return CYRILLIC;
        if (cp >= 0x0530 && cp <= 0x058F) return ARMENIAN;
        if (cp >= 0x0590 && cp <= 0x05FF) return HEBREW;
        if (cp >= 0x0600 && cp <= 0x06FF || cp >= 0x0750 && cp <= 0x077F
            || cp >= 0x08A0 && cp <= 0x08FF || cp >= 0xFB50 && cp <= 0xFDFF
            || cp >= 0xFE70 && cp <= 0xFEFF) return ARABIC;
        if (cp >= 0x0780 && cp <= 0x07BF) return THAANA;
        if (cp >= 0x0900 && cp <= 0x097F) return DEVANAGARI;
        if (cp >= 0x0980 && cp <= 0x09FF) return BENGALI;
        if (cp >= 0x0A00 && cp <= 0x0A7F) return GURMUKHI;
        if (cp >= 0x0A80 && cp <= 0x0AFF) return GUJARATI;
        if (cp >= 0x0B00 && cp <= 0x0B7F) return ORIYA;
        if (cp >= 0x0B80 && cp <= 0x0BFF) return TAMIL;
        if (cp >= 0x0C00 && cp <= 0x0C7F) return TELUGU;
        if (cp >= 0x0C80 && cp <= 0x0CFF) return KANNADA;
        if (cp >= 0x0D00 && cp <= 0x0D7F) return MALAYALAM;
        if (cp >= 0x0D80 && cp <= 0x0DFF) return SINHALA;
        if (cp >= 0x0E00 && cp <= 0x0E7F) return THAI;
        if (cp >= 0x0E80 && cp <= 0x0EFF) return LAO;
        if (cp >= 0x0F00 && cp <= 0x0FFF) return TIBETAN;
        if (cp >= 0x1000 && cp <= 0x109F) return MYANMAR;
        if (cp >= 0x10A0 && cp <= 0x10FF || cp >= 0x1C90 && cp <= 0x1CBF) return GEORGIAN;
        if (cp >= 0x1200 && cp <= 0x137F) return ETHIOPIC;
        if (cp >= 0x1780 && cp <= 0x17FF) return KHMER;
        if (cp >= 0x1100 && cp <= 0x11FF || cp >= 0x3130 && cp <= 0x318F
            || cp >= 0xAC00 && cp <= 0xD7AF) return HANGUL;
        if (cp >= 0x3040 && cp <= 0x309F) return HIRAGANA;
        if (cp >= 0x30A0 && cp <= 0x30FF || cp >= 0x31F0 && cp <= 0x31FF) return KATAKANA;
        if (cp >= 0x3400 && cp <= 0x4DBF || cp >= 0x4E00 && cp <= 0x9FFF
            || cp >= 0xF900 && cp <= 0xFAFF || cp >= 0x20000 && cp <= 0x2FA1F) return HAN;
        return OTHER;
    }

    private static String latin(String text) {
        for (int i = 0; i < text.length(); i++) {
            final char c = text.charAt(i);
            if (c >= 0x1EA0 && c <= 0x1EF9 || c == 0x01A1 || c == 0x01B0) {
                return "vi";
            }
        }
        return score(text, LATIN_CANDIDATES, LATIN_INDEX);
    }

    private static String cyrillic(String text) {
        if (contains(text, "ў")) return "be";
        if (contains(text, "ґїє")) return "uk";
        if (contains(text, "ѓќѕ")) return "mk";
        if (contains(text, "ђћџљњј")) return "sr";
        if (contains(text, "ӣӯҷ")) return "tg";
        if (contains(text, "әұқғһ")) return "kk";
        if (contains(text, "өү")) return "mn";
        final String scored = score(text, CYRILLIC_CANDIDATES, CYRILLIC_INDEX);
        if (scored != null) {
            return scored;
        }
        if (contains(text, "і")) {
            return "uk";
        }
        if (contains(text, "ъ") && !contains(text, "ыэё")) {
            return "bg";
        }
        return "ru";
    }

    private static String arabic(String text) {
        if (contains(text, "ښډټړږځڅڼ")) return "ps";
        if (contains(text, "ٹڈڑںےہ")) return "ur";
        if (contains(text, "ڕڵۆێ")) return "ckb";
        if (contains(text, "ۇۈۋ")) return "ug";
        if (contains(text, "پچژگکی")) return "fa";
        return "ar";
    }

    private static String devanagari(String text) {
        if (contains(text, "ळ") || text.contains("आहे")) return "mr";
        if (containsWord(text, "छ") || containsWord(text, "छु")
            || text.contains("छैन") || text.contains("छन्") || text.contains("हुन्छ")
            || text.contains("गर्नु") || text.contains("गर्छ") || text.contains("तपाईं")) return "ne";
        return "hi";
    }

    private static boolean contains(String text, String chars) {
        for (int i = 0; i < chars.length(); i++) {
            if (text.indexOf(chars.charAt(i)) >= 0) {
                return true;
            }
        }
        return false;
    }

    private static boolean isWordCharacter(char c) {
        if (Character.isLetter(c)) {
            return true;
        }
        final int type = Character.getType(c);
        return type == Character.NON_SPACING_MARK || type == Character.COMBINING_SPACING_MARK;
    }

    private static boolean containsWord(String text, String word) {
        int from = 0;
        while (true) {
            final int at = text.indexOf(word, from);
            if (at < 0) {
                return false;
            }
            final int end = at + word.length();
            if ((at == 0 || !isWordCharacter(text.charAt(at - 1)))
                && (end == text.length() || !isWordCharacter(text.charAt(end)))) {
                return true;
            }
            from = at + 1;
        }
    }

    private static String score(String text, String[][] candidates, HashMap<String, int[]> index) {
        final double[] scores = new double[candidates.length];

        final StringBuilder token = new StringBuilder();
        for (int i = 0; i <= text.length(); i++) {
            final char c = i < text.length() ? text.charAt(i) : ' ';
            if (Character.isLetter(c)) {
                token.append(c);
                continue;
            }
            if (token.length() > 0) {
                final int[] owners = index.get(token.toString());
                if (owners != null) {
                    final double weight = (token.length() >= 4 ? 2.0 : token.length() == 3 ? 1.5 : 1.0) / owners.length;
                    for (int owner : owners) {
                        scores[owner] += weight;
                    }
                }
                token.setLength(0);
            }
        }

        for (int i = 0; i < candidates.length; i++) {
            final String letters = candidates[i][LETTERS];
            int hits = 0;
            for (int j = 0; j < letters.length() && hits < 3; j++) {
                if (text.indexOf(letters.charAt(j)) >= 0) {
                    hits++;
                }
            }
            scores[i] += hits;
        }

        int best = -1;
        double bestScore = 0, secondScore = 0;
        for (int i = 0; i < scores.length; i++) {
            if (scores[i] > bestScore) {
                secondScore = bestScore;
                bestScore = scores[i];
                best = i;
            } else if (scores[i] > secondScore) {
                secondScore = scores[i];
            }
        }
        if (best < 0 || bestScore < MIN_SCORE || bestScore - secondScore < MIN_MARGIN) {
            return null;
        }
        return candidates[best][CODE];
    }

    private static HashMap<String, int[]> buildIndex(String[][] candidates) {
        final HashMap<String, ArrayList<Integer>> owners = new HashMap<>();
        for (int i = 0; i < candidates.length; i++) {
            for (String word : candidates[i][WORDS].split(" ")) {
                ArrayList<Integer> list = owners.get(word);
                if (list == null) {
                    owners.put(word, list = new ArrayList<>());
                }
                list.add(i);
            }
        }
        final HashMap<String, int[]> index = new HashMap<>(owners.size());
        for (Map.Entry<String, ArrayList<Integer>> entry : owners.entrySet()) {
            final ArrayList<Integer> list = entry.getValue();
            final int[] packed = new int[list.size()];
            for (int i = 0; i < packed.length; i++) {
                packed[i] = list.get(i);
            }
            index.put(entry.getKey(), packed);
        }
        return index;
    }
}
