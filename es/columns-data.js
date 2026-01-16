// Datos de Columnas de Economía - Español
const COLUMNS_DATA = [
    {
        id: '01',
        title: 'Las Dos Caras de la Inflación',
        subtitle: 'Cuando el gobierno imprime dinero y tu salario desaparece',
        description: 'Tu salario subió, pero ¿por qué tu billetera está vacía? Aprende a proteger tus activos en la era de la inflación desde perspectivas macro y microeconómicas.',
        readTime: 5,
        keywords: 'inflación, aumento de precios, macroeconomía, microeconomía, política monetaria, tasas de interés',
        content: `
            <section class="mb-8">
                <h2 class="text-2xl font-bold text-slate-800 mb-4">1. Introducción: ¿Por qué mi cuenta bancaria siempre está vacía?</h2>
                <p class="text-slate-700 leading-relaxed mb-4">El empleado A recibió un aumento del 3% este año. Aunque los números muestran que sus ingresos aumentaron, después de hacer compras y comer fuera algunas veces, el saldo bancario se agota más rápido que el año pasado. Esto no es solo una sensación.</p>
                <p class="text-slate-700 leading-relaxed mb-4">Esto sucede porque las olas de la <strong>Macroeconomía</strong>, que trata el flujo económico general de una nación, han chocado con el reino de la <strong>Microeconomía</strong>, que gobierna las finanzas de los hogares individuales.</p>
            </section>
            <section class="mb-8">
                <h2 class="text-2xl font-bold text-slate-800 mb-4">2. Perspectiva Macroeconómica: "El dinero repartido por el gobierno vuelve como olas"</h2>
                <p class="text-slate-700 leading-relaxed mb-4">La macroeconomía observa el 'bosque' de toda la nación. Aquí, la inflación se trata de controlar las compuertas masivas de la oferta monetaria y las tasas de interés.</p>
                <div class="bg-blue-50 rounded-xl p-6 mb-4">
                    <h3 class="font-semibold text-blue-800 mb-2">Caso Real: Ayudas gubernamentales y liquidez del mercado</h3>
                    <p class="text-slate-700">Durante la pandemia, los gobiernos de todo el mundo inyectaron fondos masivos en el mercado para prevenir la parálisis económica. Desde una perspectiva macro, esto apoyó la 'demanda agregada', pero simultáneamente redujo la escasez de moneda.</p>
                </div>
            </section>
            <section class="mb-8">
                <h2 class="text-2xl font-bold text-slate-800 mb-4">3. Perspectiva Microeconómica: "Estrategias de supervivencia ocultas detrás de los precios del menú"</h2>
                <p class="text-slate-700 leading-relaxed mb-4">La microeconomía observa los 'árboles' individuales. En la tormenta de aumentos de precios a nivel macro, comerciantes y consumidores intentan sobrevivir a su manera.</p>
                <div class="bg-green-50 rounded-xl p-6 mb-4">
                    <h3 class="font-semibold text-green-800 mb-2">Caso Real: Dilemas del restaurante y 'poder de fijación de precios'</h3>
                    <p class="text-slate-700">Cuando los costos de los ingredientes suben macroeconómicamente, ¿todos los restaurantes suben los precios igual? No. Desde una perspectiva micro, los restaurantes con comida y servicio excepcionales (negocios con poder de fijación de precios) suben los precios con confianza.</p>
                </div>
            </section>
            <section class="mb-8">
                <h2 class="text-2xl font-bold text-slate-800 mb-4">4. La Colisión de lo Macro y lo Micro: La 'Trampa de los promedios' y la Reduflación</h2>
                <p class="text-slate-700 leading-relaxed mb-4">El gobierno afirma que "la tasa de inflación se ha desacelerado" basándose en indicadores macro, pero los precios que siente la gente común siguen siendo altos.</p>
                <div class="bg-yellow-50 rounded-xl p-6 mb-4">
                    <h3 class="font-semibold text-yellow-800 mb-2">Caso Real: 'Aire' en las bolsas de snacks y Reduflación</h3>
                    <p class="text-slate-700">Cuando el gobierno monitorea los precios (macro), las empresas a nivel micro usan la estrategia de <strong>'Reduflación'</strong>: mantener los precios iguales mientras reducen la cantidad.</p>
                </div>
            </section>
            <section class="mb-8">
                <h2 class="text-2xl font-bold text-slate-800 mb-4">5. Estrategias de Supervivencia contra la Inflación para Personas Comunes</h2>
                <p class="text-slate-700 leading-relaxed mb-4">Debes leer las tendencias macro y modificar los comportamientos micro en consecuencia.</p>
                <div class="space-y-4">
                    <div class="bg-slate-50 rounded-xl p-6">
                        <h3 class="font-semibold text-slate-800 mb-2">Visión Macro (Verificar el clima del mercado)</h3>
                        <p class="text-slate-700">Durante los aumentos de tasas de interés (macro), pagar deudas en lugar de expandir activos a través de préstamos riesgosos es la opción micro que produce el mayor rendimiento.</p>
                    </div>
                    <div class="bg-slate-50 rounded-xl p-6">
                        <h3 class="font-semibold text-slate-800 mb-2">Visión Micro (Encontrar sustitutos)</h3>
                        <p class="text-slate-700">En lugar de aferrarse a artículos cuyos precios se han disparado debido a la inflación, descubre activamente sustitutos rentables que puedan mantener tu utilidad (satisfacción).</p>
                    </div>
                </div>
            </section>
            <section class="mb-8">
                <h2 class="text-2xl font-bold text-slate-800 mb-4">6. Conclusión: La alfabetización económica protege tu billetera</h2>
                <p class="text-slate-700 leading-relaxed">La inflación no es solo jerga económica. Es una realidad que ocurre en el punto donde tu elección del almuerzo de hoy se encuentra con el valor de la moneda emitida por el gobierno.</p>
            </section>
        `
    },
    {
        id: '02',
        title: 'El Terror de la Estanflación',
        subtitle: "Economía fría, precios calientes: Una 'convivencia incómoda'",
        description: 'Los negocios van mal, pero ¿por qué siguen subiendo los precios? Analizamos las causas y estrategias para enfrentar la estanflación.',
        readTime: 5,
        keywords: 'estanflación, recesión, inflación, shock de oferta, política monetaria',
        content: `
            <section class="mb-8">
                <h2 class="text-2xl font-bold text-slate-800 mb-4">1. Introducción: "¿Por qué suben los precios si los negocios van mal?"</h2>
                <p class="text-slate-700 leading-relaxed mb-4">Normalmente aprendemos que cuando la economía va mal, los productos no se venden y los precios bajan, y cuando va bien, el consumo aumenta y los precios suben.</p>
                <p class="text-slate-700 leading-relaxed">Sin embargo, la realidad que vivimos recientemente es bastante diferente. La economía llama a este fenómeno <strong>Estanflación</strong>. Analicemos este peor escenario donde el estancamiento económico a nivel macro se combina con la explosión de costos a nivel micro.</p>
            </section>
            <section class="mb-8">
                <h2 class="text-2xl font-bold text-slate-800 mb-4">2. Perspectiva Macroeconómica: Sobrecarga del sistema y dilema político</h2>
                <p class="text-slate-700 leading-relaxed mb-4">La macroeconomía es el estudio de verificar si la máquina gigante llamada nación funciona correctamente.</p>
                <div class="bg-red-50 rounded-xl p-6 mb-4">
                    <h3 class="font-semibold text-red-800 mb-2">Caso Real: Aumento de precios energéticos y 'Shock de oferta'</h3>
                    <p class="text-slate-700">El principal culpable de la estanflación suele estar en el 'lado de la oferta'. Por ejemplo, supongamos que los precios del petróleo o gas natural aumentan macroeconómicamente debido a conflictos geopolíticos.</p>
                </div>
            </section>
            <section class="mb-8">
                <h2 class="text-2xl font-bold text-slate-800 mb-4">3. Perspectiva Microeconómica: Cambios desesperados de los actores individuales para sobrevivir</h2>
                <p class="text-slate-700 leading-relaxed mb-4">La microeconomía examina bajo un microscopio cómo los dueños de negocios locales y las familias cambian su comportamiento en estas tormentas masivas.</p>
            </section>
            <section class="mb-8">
                <h2 class="text-2xl font-bold text-slate-800 mb-4">4. Conectando lo Macro y lo Micro: El dúo del 'Miedo al desempleo' y las 'Dificultades financieras'</h2>
                <p class="text-slate-700 leading-relaxed mb-4">La verdadera razón por la que la estanflación es aterradora es que cuando el desempleo (fenómeno macro) se encuentra con las dificultades financieras (sufrimiento micro), crean sinergia.</p>
            </section>
            <section class="mb-8">
                <h2 class="text-2xl font-bold text-slate-800 mb-4">5. Estrategias de Supervivencia para la Era de Estanflación</h2>
                <p class="text-slate-700 leading-relaxed mb-4">No puedes cambiar la recesión macro, pero puedes cambiar la estructura micro de tu vida.</p>
                <div class="space-y-4">
                    <div class="bg-slate-50 rounded-xl p-6">
                        <h3 class="font-semibold text-slate-800 mb-2">Maximizar la 'Eficiencia de costos' a nivel micro</h3>
                        <p class="text-slate-700">Mientras la inflación persista, el efectivo pierde valor. Sin embargo, durante las recesiones, los ingresos se vuelven inestables, así que debes liquidar activos innecesarios y reducir drásticamente los gastos fijos.</p>
                    </div>
                </div>
            </section>
            <section class="mb-8">
                <h2 class="text-2xl font-bold text-slate-800 mb-4">6. Conclusión: Cabeza fría y gestión cálida de la cesta de la compra</h2>
                <p class="text-slate-700 leading-relaxed">La estanflación puede ser resultado de un fracaso político para las naciones, pero para los individuos, es un momento que pone a prueba la paciencia económica.</p>
            </section>
        `
    },
    {
        id: '03',
        title: 'Expansión y Contracción Cuantitativa',
        subtitle: 'Olas de dinero agitadas por el gobierno: ¿Está mi vida a salvo?',
        description: 'Dicen que hay demasiado dinero en el mundo, pero ¿por qué no tengo nada? Analizando cómo las políticas de expansión y contracción cuantitativa afectan los activos individuales.',
        readTime: 5,
        keywords: 'expansión cuantitativa, contracción, política monetaria, tasas de interés, precios de activos, estrategia de inversión',
        content: `
            <section class="mb-8">
                <h2 class="text-2xl font-bold text-slate-800 mb-4">1. Introducción: "Dicen que hay demasiado dinero, pero ¿por qué no tengo nada?"</h2>
                <p class="text-slate-700 leading-relaxed mb-4">Hace apenas unos años, estábamos obsesionados con frases como "eres tonto si no inviertes en acciones". De repente, la atmósfera cambió a "el efectivo es el rey" y "las tasas de interés son demasiado aterradoras para hacer algo".</p>
                <p class="text-slate-700 leading-relaxed">Esta diferencia extrema de temperatura ocurre debido a la <strong>Expansión Cuantitativa y la Contracción</strong>, políticas macroeconómicas donde el gobierno afloja y aprieta la oferta monetaria.</p>
            </section>
            <section class="mb-8">
                <h2 class="text-2xl font-bold text-slate-800 mb-4">2. Perspectiva Macroeconómica: "Abriendo las compuertas para regar la tierra seca"</h2>
                <p class="text-slate-700 leading-relaxed mb-4">En macroeconomía, la Expansión Cuantitativa (QE) es como 'RCP' donde el banco central bombea dinero directamente al mercado para prevenir la recesión económica.</p>
            </section>
            <section class="mb-8">
                <h2 class="text-2xl font-bold text-slate-800 mb-4">3. Perspectiva Microeconómica: "Apalancamiento, bombas de tasas de interés y reestructuración de activos"</h2>
                <p class="text-slate-700 leading-relaxed mb-4">La microeconomía rastrea qué 'elecciones' hacen los hogares individuales e inversores en medio de estas olas macro.</p>
            </section>
            <section class="mb-8">
                <h2 class="text-2xl font-bold text-slate-800 mb-4">4. Conectando lo Macro y lo Micro: 'Transferencia de riqueza' y su sombra</h2>
                <p class="text-slate-700 leading-relaxed mb-4">La QE y la contracción van más allá de simplemente ajustar la cantidad de dinero; traen redistribución de riqueza entre clases.</p>
            </section>
            <section class="mb-8">
                <h2 class="text-2xl font-bold text-slate-800 mb-4">5. Estrategias para Surfear las Olas de Liquidez</h2>
                <p class="text-slate-700 leading-relaxed mb-4">Si no puedes cambiar el clima macro, debes reforzar tu barco (activos micro) para que sea resistente.</p>
            </section>
            <section class="mb-8">
                <h2 class="text-2xl font-bold text-slate-800 mb-4">6. Conclusión: La política del gobierno es el pronóstico del tiempo, mi elección es el paraguas</h2>
                <p class="text-slate-700 leading-relaxed">La QE y la contracción son como estaciones económicas que no podemos rechazar. La rueda gigante de la macroeconomía nunca se detiene.</p>
            </section>
        `
    }
];

// I18N de Columnas
const COLUMNS_I18N = {
    TITLE: 'Columnas de Economía',
    SUBTITLE: 'De lo Micro a lo Macro',
    DESCRIPTION: 'Aprende conocimientos económicos prácticos que puedes aplicar en la vida diaria',
    SEARCH_PLACEHOLDER: 'Buscar columnas...',
    READ_MORE: 'Leer más',
    READ_TIME: 'min de lectura',
    NO_RESULTS: 'No se encontraron resultados',
    BACK_TO_LIST: 'Volver a la lista',
    SHARE: 'Compartir',
    RELATED_COLUMNS: 'Columnas relacionadas',
    PREV_COLUMN: 'Anterior',
    NEXT_COLUMN: 'Siguiente'
};
