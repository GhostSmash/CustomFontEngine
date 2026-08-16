package com.customfont.engine;

import android.app.Activity;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.TextView;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * FontEngine — движок принудительной подмены шрифта для exteraGram/Telegram
 * клиентов, загружаемый плагином "Custom Font" через InMemoryDexClassLoader.
 *
 * Почему это нужно на Java, а не на Python (Chaquopy):
 *
 *  1. Большая часть текста в Telegram-клиенте (в частности названия чатов
 *     в списке, DialogCell) рисуется НЕ через View.setTypeface(), а через
 *     статические объекты Paint, хранящиеся в классе
 *     org.telegram.messenger.AndroidUtilities как отдельные поля
 *     (dialogs_namePaint и подобные, в зависимости от версии клиента).
 *     Обход дерева View в принципе не видит эти Paint — они не привязаны
 *     ни к одному конкретному View, а разделяются между многими ячейками
 *     RecyclerView, переиспользующими один и тот же Paint на перерисовку.
 *
 *  2. Каждый вызов reflection (getDeclaredField/invoke) через Chaquopy
 *     проходит через границу Python<->JVM, что ощутимо медленнее вызова
 *     того же кода изнутри JVM напрямую. При частом переприменении (на
 *     каждый кадр разметки) это и вызывало лаги.
 *
 * Данный класс:
 *  - Патчит статические поля Typeface (DEFAULT/DEFAULT_BOLD/...).
 *  - Патчит все static Paint-поля класса Theme, у которых Paint содержит
 *    Typeface (через сканирование полей по рефлексии один раз при
 *    установке, дальше работает по кэшированному списку).
 *  - Обходит дерево View по требованию, но КЭШИРУЕТ обработанные View
 *    через IdentityHashMap, чтобы не обрабатывать их повторно, и
 *    использует WeakHashMap, чтобы не удерживать View от сборки мусора.
 *  - Не хукает системные методы через Xposed/EdXposed-стиль (не требует
 *    прав на модификацию байткода) — использует только reflection на
 *    собственных данных Telegram-классов, что безопаснее для стабильности.
 */
public class FontEngine {

    // Версия DEX-движка — должна совпадать с версией плагина (FONT_ENGINE_MIN_VERSION..MAX_VERSION)
    public static final String VERSION = "1.4.0";

    private static volatile Typeface currentTypeface = null;
    private static volatile boolean initialized = false;

    // IdentityHashMap оборачиваем через WeakHashMap-семантику вручную —
    // используем java.util.WeakHashMap<View, Typeface>, чтобы не мешать GC
    // собирать выгруженные View (в отличие от чистого IdentityHashMap,
    // который держит сильные ссылки на ключи).
    private static final Map<View, Typeface> appliedViews =
            java.util.Collections.synchronizedMap(new WeakHashMap<View, Typeface>());

    // Кэш reflection-полей типа Paint по классу (может быть несколько на
    // класс — например namePaint + statusPaint в одной ячейке), чтобы не
    // сканировать иерархию заново для каждого элемента при каждом проходе.
    private static final Map<Class<?>, Field[]> paintFieldCache =
            new java.util.concurrent.ConcurrentHashMap<Class<?>, Field[]>();

    // Кэш статических Paint-полей класса Theme, найденных один раз при
    // первой установке — дальше просто перебираем этот список.
    private static volatile Field[] themeStaticPaintFields = null;
    private static volatile Field[] themeStaticPaintArrayFields = null;

    private static volatile float textScale = 1.0f;
    private static volatile float letterSpacing = 0f;
    private static volatile boolean forceBoldHeaders = false;

    /**
     * Задаёт дополнительные параметры оформления текста — масштаб, интервал
     * между буквами, принудительную жирность заголовков. Применяются в
     * applyRecursive вместе с обычной подменой typeface, без отдельного
     * прохода по дереву.
     *
     * Диапазоны намеренно НЕ валидируются здесь на уровне движка — этим
     * занимается Python-сторона перед вызовом (см. _on_dev_scale_change и
     * соседние методы в плагине), чтобы явно показать пользователю причину
     * отказа. Движок принимает уже провалидированные значения как есть.
     */
    public static void setTextParams(float scale, float spacing, boolean boldHeaders) {
        textScale = scale;
        letterSpacing = spacing;
        forceBoldHeaders = boldHeaders;
        textParamsVersion++;
        // Сбрасываем кэш обработанных View целиком — при смене dev-параметров
        // (масштаб/интервал/жирность) нужно перепройтись по всем элементам
        // заново, даже если typeface у них не поменялся. appliedViews хранит
        // typeface, а не версию параметров, поэтому проще инвалидировать всё.
        appliedViews.clear();
    }

    private static volatile int textParamsVersion = 0;

    /**
     * Патчит static поля класса org.telegram.messenger.AndroidUtilities —
     * там, помимо кэша typefaceCache (который правильнее чистить, а не
     * подменять — новый вызов getTypeface() создаст Typeface из нашего
     * файла заново), могут быть отдельные static Paint-поля, аналогично
     * Theme. Передаём имя класса из Python, чтобы не завязываться на
     * конкретный пакет намертво в самом движке.
     */
    public static int patchAndroidUtilitiesPaints(String androidUtilitiesClassName) {
        return patchThemePaints(androidUtilitiesClassName);
    }

    /**
     * Единая точка входа, объединяющая все шаги патчинга статики за один
     * вызов из Python — сокращает количество пересечений границы Chaquopy
     * с нескольких вызовов до одного.
     */
    public static int patchAllStatics(String themeClassName, String androidUtilitiesClassName) {
        patchTypefaceStatics();
        int a = patchThemePaints(themeClassName);
        int b = 0;
        if (androidUtilitiesClassName != null && !androidUtilitiesClassName.equals(themeClassName)) {
            b = patchThemePaints(androidUtilitiesClassName);
        }
        return a + b;
    }

    private FontEngine() {
    }

    /**
     * Точка входа, вызываемая из Python-обёртки (через reflection —
     * getDeclaredMethod("init", Typeface.class).invoke(null, typeface)).
     */
    public static void init(Typeface typeface) {
        currentTypeface = typeface;
        initialized = true;
    }

    public static boolean isInitialized() {
        return initialized;
    }

    public static void clearFont() {
        currentTypeface = null;
        appliedViews.clear();
    }

    /**
     * Подменяет статические поля Typeface.DEFAULT/DEFAULT_BOLD/SANS_SERIF/
     * SERIF/MONOSPACE. Часть кода Android/Telegram берёт шрифт напрямую
     * отсюда, минуя getTypeface().
     */
    public static void patchTypefaceStatics() {
        if (currentTypeface == null) return;
        String[] fieldNames = {"DEFAULT", "DEFAULT_BOLD", "SANS_SERIF", "SERIF", "MONOSPACE"};
        for (String name : fieldNames) {
            try {
                Field f = Typeface.class.getDeclaredField(name);
                setStaticFinal(f, currentTypeface);
            } catch (Throwable ignored) {
                // Поле может отсутствовать на некоторых версиях Android —
                // пропускаем, остальные поля всё равно попробуем пропатчить.
            }
        }
    }

    /**
     * Сканирует класс org.telegram.ui.ActionBar.Theme на предмет всех
     * static полей типа Paint или Paint[] и подменяет typeface в каждом
     * найденном Paint. Именно здесь рисуются названия чатов, тексты
     * сообщений в предпросмотре и другие элементы, которые не привязаны
     * к конкретному View.
     */
    public static int patchThemePaints(String themeClassName) {
        if (currentTypeface == null) return 0;
        int patched = 0;
        try {
            Class<?> themeClass = Class.forName(themeClassName);

            if (themeStaticPaintFields == null || themeStaticPaintArrayFields == null) {
                java.util.List<Field> singlePaintFields = new java.util.ArrayList<Field>();
                java.util.List<Field> arrayPaintFields = new java.util.ArrayList<Field>();

                for (Field f : themeClass.getDeclaredFields()) {
                    if (!Modifier.isStatic(f.getModifiers())) continue;
                    Class<?> type = f.getType();
                    f.setAccessible(true);
                    if (Paint.class.isAssignableFrom(type)) {
                        singlePaintFields.add(f);
                    } else if (type.isArray() && Paint.class.isAssignableFrom(type.getComponentType())) {
                        arrayPaintFields.add(f);
                    }
                }
                themeStaticPaintFields = singlePaintFields.toArray(new Field[0]);
                themeStaticPaintArrayFields = arrayPaintFields.toArray(new Field[0]);
            }

            for (Field f : themeStaticPaintFields) {
                try {
                    Object val = f.get(null);
                    if (val instanceof Paint) {
                        ((Paint) val).setTypeface(currentTypeface);
                        patched++;
                    }
                } catch (Throwable ignored) {
                }
            }

            for (Field f : themeStaticPaintArrayFields) {
                try {
                    Object arrVal = f.get(null);
                    if (arrVal == null) continue;
                    int len = java.lang.reflect.Array.getLength(arrVal);
                    for (int i = 0; i < len; i++) {
                        Object item = java.lang.reflect.Array.get(arrVal, i);
                        if (item instanceof Paint) {
                            ((Paint) item).setTypeface(currentTypeface);
                            patched++;
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable e) {
            // Класс Theme не найден или сигнатура отличается на этой сборке
            // клиента — просто ничего не патчим здесь, View-обход остаётся
            // рабочим запасным путём.
        }
        return patched;
    }

    /**
     * Рекурсивный обход дерева View — тот же принцип, что был в Python, но
     * работает нативно внутри JVM, без пересечения границы Chaquopy на
     * каждый элемент. Кэширует уже обработанные View через WeakHashMap.
     */
    public static int applyToTree(View root) {
        if (currentTypeface == null || root == null) return 0;
        return applyRecursive(root, currentTypeface);
    }

    private static int applyRecursive(View view, Typeface typeface) {
        int count = 0;

        Typeface already = appliedViews.get(view);
        if (already != typeface) {
            if (view instanceof TextView) {
                TextView tv = (TextView) view;
                Typeface current = tv.getTypeface();
                int style = current != null ? current.getStyle() : 0;
                tv.setTypeface(typeface, style);
                applyTextParams(tv);
                appliedViews.put(view, typeface);
                count++;
            } else {
                if (applyToCustomClass(view, typeface)) {
                    appliedViews.put(view, typeface);
                    count++;
                }
            }
        }

        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            int childCount = group.getChildCount();
            for (int i = 0; i < childCount; i++) {
                View child = group.getChildAt(i);
                if (child != null) {
                    count += applyRecursive(child, typeface);
                }
            }
        }

        return count;
    }

    private static void applyTextParams(TextView tv) {
        try {
            // textScale масштабирует относительно ТЕКУЩЕГО размера, а не
            // абсолютно — так каждый элемент сохраняет свою исходную
            // иерархию размеров (заголовки крупнее подписей и т.п.),
            // просто пропорционально увеличиваясь или уменьшаясь.
            if (Math.abs(textScale - 1.0f) > 0.001f) {
                float currentSizePx = tv.getTextSize();
                // Защита от накопительного масштабирования при повторных
                // проходах: используем тег на View, чтобы помнить
                // "базовый" размер до масштабирования, а не масштабировать
                // уже смасштабированное значение на каждый проход.
                Object baseTag = tv.getTag(BASE_SIZE_TAG_KEY);
                float basePx;
                if (baseTag instanceof Float) {
                    basePx = (Float) baseTag;
                } else {
                    basePx = currentSizePx;
                    tv.setTag(BASE_SIZE_TAG_KEY, basePx);
                }
                tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, basePx * textScale);
            }

            if (Math.abs(letterSpacing) > 0.0001f) {
                tv.setLetterSpacing(letterSpacing);
            }

            if (forceBoldHeaders) {
                String className = tv.getClass().getName();
                boolean looksLikeHeader = className.contains("ActionBar")
                        || className.toLowerCase().contains("title")
                        || className.toLowerCase().contains("header");
                if (looksLikeHeader) {
                    Typeface current = tv.getTypeface();
                    tv.setTypeface(current, Typeface.BOLD);
                }
            }
        } catch (Throwable ignored) {
            // Метод недоступен на этой версии Android/клиента (например,
            // setLetterSpacing появился только в API 21+) — пропускаем этот
            // конкретный параметр, остальные продолжают применяться.
        }
    }

    // Используем стабильный отрицательный int как ключ тега — не пересекается
    // с обычными view tag ID, которые генерируются через View.generateViewId()
    // и всегда положительны.
    private static final int BASE_SIZE_TAG_KEY = -87234321;

    private static boolean applyToCustomClass(View view, Typeface typeface) {
        Class<?> cls = view.getClass();
        String name = cls.getName();

        // Работаем только с классами Telegram-клиента (org.telegram.*) —
        // трогать сторонние/системные виджеты со своими Paint-полями через
        // reflection рискованно и не нужно, они и так рисуют системным
        // шрифтом по умолчанию, а не кастомным Telegram-текстом.
        if (!name.startsWith("org.telegram.")) return false;

        try {
            java.lang.reflect.Method m = cls.getMethod("setTypeface", Typeface.class);
            m.invoke(view, typeface);
            return true;
        } catch (Throwable ignored) {
        }

        Field[] cached = paintFieldCache.get(cls);
        if (cached != null) {
            boolean any = false;
            for (Field f : cached) {
                if (trySetPaintField(f, view, typeface)) any = true;
            }
            return any;
        }

        // Универсальное сканирование: ищем ВСЕ поля типа Paint/TextPaint во
        // всей иерархии классов (сам класс + все родители до Object), а не
        // только по фиксированному списку имён ("textPaint", "paint" и
        // т.п.). Реальные имена полей отличаются между версиями клиента и
        // между разными Cell-классами (namePaint, messagePaint, timePaint,
        // authorPaint и десятки других) — угадывать их по одному непрактично,
        // сканирование по типу поля работает независимо от имени и версии.
        java.util.List<Field> found = new java.util.ArrayList<Field>();
        Class<?> walker = cls;
        while (walker != null && walker != Object.class) {
            for (Field f : walker.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue; // статику здесь не трогаем, она через patchThemePaints
                Class<?> type = f.getType();
                if (Paint.class.isAssignableFrom(type)) {
                    f.setAccessible(true);
                    found.add(f);
                }
            }
            walker = walker.getSuperclass();
        }

        if (found.isEmpty()) {
            paintFieldCache.put(cls, new Field[0]);
            return false;
        }

        boolean any = false;
        for (Field f : found) {
            if (trySetPaintField(f, view, typeface)) any = true;
        }
        paintFieldCache.put(cls, found.toArray(new Field[0]));
        return any;
    }

    private static boolean trySetPaintField(Field field, View view, Typeface typeface) {
        try {
            Object val = field.get(view);
            if (val instanceof Paint) {
                ((Paint) val).setTypeface(typeface);
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /**
     * Снимает флаг final с поля через модификаторы и записывает значение.
     * Работает для static final полей, которые в противном случае Field.set
     * молча игнорирует.
     */
    private static void setStaticFinal(Field field, Object value) throws Exception {
        field.setAccessible(true);
        try {
            Field modifiersField = Field.class.getDeclaredField("accessFlags");
            modifiersField.setAccessible(true);
            int mods = modifiersField.getInt(field);
            modifiersField.setInt(field, mods & ~Modifier.FINAL);
        } catch (Throwable ignored) {
            // На некоторых версиях ART доступ к 'accessFlags' блокируется
            // hidden-API ограничениями — пробуем записать поле напрямую,
            // на части устройств это всё ещё срабатывает без снятия final.
        }
        field.set(null, value);
    }

    /**
     * Вешает единый OnGlobalLayoutListener на decorView activity с
     * встроенным debounce через View.post — планирует не более одного
     * прохода по дереву за кадр, схлопывая частые вызовы во время скролла
     * и анимаций.
     */
    public static void attachAutoReapply(final Activity activity) {
        if (activity == null) return;
        final View decorView = activity.getWindow().getDecorView();
        final ViewTreeObserver observer = decorView.getViewTreeObserver();
        if (!observer.isAlive()) return;

        final boolean[] scheduled = {false};

        observer.addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                if (currentTypeface == null) return;
                if (scheduled[0]) return;
                scheduled[0] = true;
                decorView.post(new Runnable() {
                    @Override
                    public void run() {
                        scheduled[0] = false;
                        applyToTree(decorView);
                    }
                });
            }
        });
    }
}
