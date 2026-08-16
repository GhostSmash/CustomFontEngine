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

    private static volatile Typeface currentTypeface = null;
    private static volatile boolean initialized = false;

    // IdentityHashMap оборачиваем через WeakHashMap-семантику вручную —
    // используем java.util.WeakHashMap<View, Typeface>, чтобы не мешать GC
    // собирать выгруженные View (в отличие от чистого IdentityHashMap,
    // который держит сильные ссылки на ключи).
    private static final Map<View, Typeface> appliedViews =
            java.util.Collections.synchronizedMap(new WeakHashMap<View, Typeface>());

    // Кэш reflection-полей textPaint/paint по классу, чтобы не искать их
    // заново для каждого элемента при каждом проходе.
    private static final Map<Class<?>, Field> paintFieldCache =
            new java.util.concurrent.ConcurrentHashMap<Class<?>, Field>();

    // Кэш статических Paint-полей класса Theme, найденных один раз при
    // первой установке — дальше просто перебираем этот список.
    private static volatile Field[] themeStaticPaintFields = null;
    private static volatile Field[] themeStaticPaintArrayFields = null;

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

    private static boolean applyToCustomClass(View view, Typeface typeface) {
        Class<?> cls = view.getClass();
        String name = cls.getName();

        boolean known = name.equals("org.telegram.ui.ActionBar.SimpleTextView")
                || name.equals("org.telegram.ui.Components.Text")
                || name.equals("org.telegram.ui.Components.AnimatedTextView")
                || name.equals("org.telegram.ui.Cells.DialogCell");

        if (!known) return false;

        try {
            java.lang.reflect.Method m = cls.getMethod("setTypeface", Typeface.class);
            m.invoke(view, typeface);
            return true;
        } catch (Throwable ignored) {
        }

        Field cached = paintFieldCache.get(cls);
        if (cached != null) {
            return trySetPaintField(cached, view, typeface);
        }

        String[] candidateFields = {"textPaint", "paint", "namePaint", "titlePaint"};
        for (String fieldName : candidateFields) {
            try {
                Field f = cls.getDeclaredField(fieldName);
                f.setAccessible(true);
                if (trySetPaintField(f, view, typeface)) {
                    paintFieldCache.put(cls, f);
                    return true;
                }
            } catch (Throwable ignored) {
            }
        }
        return false;
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
