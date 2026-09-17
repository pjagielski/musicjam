# Ściągi dla prowadzącego

Do otwarcia na tablecie - strony w proporcjach 3:4, spis treści z linkami i zakładki w PDF.

- [`program-warsztatu.pdf`](program-warsztatu.pdf) - co omówić w każdym kroku: co pokazać, co powiedzieć,
  co piszą uczestnicy i które testy są zielone od startu. Źródło: `program-warsztatu.html`, pisane ręcznie.
- [`sciaga-rozwiazania.pdf`](sciaga-rozwiazania.pdf) - rozwiązania kroków 2-5, czyli diff `step-N` ->
  `step-N-final` w `src/main`, z sygnaturą metody nad każdą zmianą. Generowane z gałęzi.

Po zmianach w gałęziach kroków, z katalogu głównego repozytorium:

```bash
python docs/sciagi/build_sciaga.py docs/sciagi/sciaga-rozwiazania.html
python docs/sciagi/print_pdf.py docs/sciagi/sciaga-rozwiazania.html docs/sciagi/program-warsztatu.html
```

`build_sciaga.py` czyta lokalne gałęzie `step-N` i `step-N-final`, a gdy którejś nie ma - wersję z `origin`.
`print_pdf.py` drukuje przez Chrome albo Edge w trybie headless.
