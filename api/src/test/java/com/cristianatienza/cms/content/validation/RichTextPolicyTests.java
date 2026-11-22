package com.cristianatienza.cms.content.validation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Red de seguridad de la política del texto enriquecido (fase 6.4.1), independiente de los
 * «esperado» del corpus: si alguien fijara por error en el corpus que algo peligroso se admite, esto
 * fallaría igualmente. Esquemas peligrosos, codificaciones alternativas, atributos y enlaces maliciosos.
 */
class RichTextPolicyTests {

  @ParameterizedTest
  @ValueSource(strings = {
      "<script>alert(1)</script>",
      "<SCRIPT>alert(1)</SCRIPT>",
      "<img src=x onerror=alert(1)>",
      "<svg onload=alert(1)>",
      "<iframe src=\"https://evil.example\"></iframe>",
      "<style>p{}</style>",
      "<p onclick=\"alert(1)\">x</p>",
      "<p\nonmouseover=\"alert(1)\">x</p>",
      "<p style=\"background:url(javascript:alert(1))\">x</p>",
      "<a href=\"javascript:alert(1)\">x</a>",
      "<a href=\"JaVaScRiPt:alert(1)\">x</a>",
      "<a href=\" javascript:alert(1)\">x</a>",
      "<a href=\"java\tscript:alert(1)\">x</a>",
      "<a href=\"&#106;avascript:alert(1)\">x</a>",
      "<a href=\"&#x6A;avascript:alert(1)\">x</a>",
      "<a href=\"javascript&colon;alert(1)\">x</a>",
      "<a href=\"%6Aavascript:alert(1)\">x</a>",
      "<a href=\"data:text/html;base64,PHNjcmlwdD4=\">x</a>",
      "<a href=\"vbscript:msgbox(1)\">x</a>",
      "<a href=\"//evil.example\">x</a>",
      "<a href=\"/\\evil.example\">x</a>",
      "<a href=\"https:\\\\evil.example\">x</a>",
      "<a href=\"http://example.com\">x</a>",
      "<a href=\"/viajes\" onclick=\"alert(1)\">x</a>",
      "<a href='javascript:alert(1)'>x</a>",
      "<a href=javascript:alert(1)>x</a>",
      "<a title=\"x\" href=\"/viajes\">x</a>",
      "<A HREF=\"javascript:alert(1)\">x</A>",
      "<!--<script>alert(1)</script>-->",
      "<![CDATA[<script>alert(1)</script>]]>",
      "<p>x</p><",
      "< script>alert(1)</script>",
      "<a href=\"/a\">x<a href=\"/b\">y</a></a>"})
  void loPeligrosoSiempreTieneIncidencias(String html) {
    assertThat(RichTextPolicy.issues(html)).as(html).isNotEmpty();
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "javascript:alert(1)", "JAVASCRIPT:alert(1)", " javascript:alert(1)", "java\tscript:alert(1)",
      "data:text/html,x", "vbscript:x", "file:///etc/passwd", "http://example.com", "//evil.example",
      "///evil.example", "/\\evil.example", "\\\\evil.example", "https:\\\\evil.example", "/\tevil",
      "/\nevil", "/ evil", "mailto:a@b.c", "tel:1", "", "#", "?x", "viajes", "/a\"b", "/a<b"})
  void destinosNoAdmitidos(String href) {
    assertThat(RichTextPolicy.isAllowedHref(href)).as(href).isFalse();
  }

  @ParameterizedTest
  @ValueSource(strings = {"/", "/viajes", "/viajes/calas-de-mallorca/#cuando-ir", "/%2F%2Fevil.example", "#capitulo",
      "https://example.com/a?b=1#c"})
  void destinosAdmitidos(String href) {
    assertThat(RichTextPolicy.isAllowedHref(href)).as(href).isTrue();
  }

  @Test
  void lasRutasInternasNuncaCambianDeOrigen() {
    // Con barras codificadas el navegador NO cambia de host (la decodificación no es de la autoridad).
    assertThat(RichTextPolicy.isInternalPath("/%2F%2Fevil.example")).isTrue();
    assertThat(RichTextPolicy.isInternalPath("//evil.example")).isFalse();
    assertThat(RichTextPolicy.isInternalPath("/\\evil.example")).isFalse();
  }

  @Test
  void laListaBlancaEsExactamenteLaDeRichTextTs() {
    assertThat(RichTextPolicy.ALLOWED_TAGS)
        .containsExactlyInAnyOrder("p", "h3", "em", "strong", "a", "code", "ul", "ol", "li", "br");
  }
}
