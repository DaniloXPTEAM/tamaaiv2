package ai.tama.router

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShortcutParserTest {
    @Test fun `direct PT and EN picture requests preserve the actual description`() {
        assertEquals(Shortcut.Image("um pássaro azul"), ShortcutParser.parse("Gere uma imagem de um pássaro azul, por favor"))
        assertEquals(Shortcut.Image("a cat and a dog"), ShortcutParser.parse("Please draw a picture of a cat and a dog"))
        assertEquals(Shortcut.Image("uma montanha"), ShortcutParser.parse("desenha uma imagem de uma montanha"))
    }

    @Test fun `ambiguous compound or risky requests are never shortcuts`() {
        listOf(
            "gera imagem", "gera imagem de", "gera imagem de gato e abra o banco",
            "gere imagem de gato; apaga o arquivo", "gere imagem de gato e depois envie",
            "gere imagem de gato e busque o clima", "gere imagem de gato, me diga a previsão",
            "gere imagem de gato. Pesquise o clima", "gere imagem de gato caso chova",
            "gere imagem de \"gato\"", "não gere imagem de gato", "don't generate an image of a cat",
            "como gerar uma imagem de gato?", "gere imagem de gato\ne abra o navegador",
        ).forEach { assertNull(it, ShortcutParser.parse(it)) }
    }

    @Test fun `parallel app accepts faz and faça without treating a question or other action as consent`() {
        val beach = "faz uma imagem de um domingo na praia"
        assertNull(ShortcutParser.parse(beach)) // normal app keeps its prior behavior
        assertEquals(Shortcut.Image("um domingo na praia"), ShortcutParser.parse(beach, portugueseMake = true))
        assertEquals(Shortcut.Image("uma maçã"), ShortcutParser.parse("Faça uma imagem de uma maçã", portugueseMake = true))
        assertTrue(ImageRequestIntent.explicit(beach, portugueseMake = true))
        assertFalse(ImageRequestIntent.explicit(beach))
        listOf("não faz imagem de um gato", "faz imagem de um gato e abra o banco",
            "faz imagem de gato?", "se chover, faz imagem de gato").forEach {
            assertNull(it, ShortcutParser.parse(it, portugueseMake = true))
        }
        assertFalse(ImageRequestIntent.explicit("como faz uma imagem de gato?", portugueseMake = true))
    }

    @Test fun `model image intent requires an explicit positive request`() {
        assertTrue(ImageRequestIntent.explicit("Crie um cartão e gere uma imagem de um gato"))
        assertTrue(ImageRequestIntent.explicit("Please generate an image of a bird"))
        assertFalse(ImageRequestIntent.explicit("não gere uma imagem"))
        assertFalse(ImageRequestIntent.explicit("don't create a picture"))
        assertFalse(ImageRequestIntent.explicit("como gerar uma imagem?"))
        assertFalse(ImageRequestIntent.explicit("gere uma imagem"))
        assertFalse(ImageRequestIntent.explicit("gere uma imagem de"))
        assertFalse(ImageRequestIntent.explicit("Explique como gere uma imagem"))
        assertFalse(ImageRequestIntent.explicit("Se precisar, gere uma imagem de gato"))
        assertFalse(ImageRequestIntent.explicit("Eu disse: \"gere uma imagem\""))
        assertFalse(ImageRequestIntent.explicit("gosto de imagens"))
    }
}
