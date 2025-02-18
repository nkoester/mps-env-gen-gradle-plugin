<?xml version="1.0" encoding="UTF-8"?>
<model ref="r:017dcf3d-7d2d-474e-bc77-66aba853f2e4(ExampleLanguage.sandbox)">
  <persistence version="9" />
  <languages>
    <use id="bec5bd5e-19f4-4b9c-8557-74df5de1c0e0" name="ExampleLanguage" version="0" />
  </languages>
  <imports />
  <registry>
    <language id="92d2ea16-5a42-4fdf-a676-c7604efe3504" name="de.slisson.mps.richtext">
      <concept id="2557074442922380897" name="de.slisson.mps.richtext.structure.Text" flags="ng" index="19SGf9">
        <child id="2557074442922392302" name="words" index="19SJt6" />
      </concept>
      <concept id="2557074442922438156" name="de.slisson.mps.richtext.structure.Word" flags="ng" index="19SUe$">
        <property id="2557074442922438158" name="escapedValue" index="19SUeA" />
      </concept>
    </language>
    <language id="bec5bd5e-19f4-4b9c-8557-74df5de1c0e0" name="ExampleLanguage">
      <concept id="4849987038620949391" name="ExampleLanguage.structure.TestConcept" flags="ng" index="2D3Coc">
        <reference id="4849987038621056473" name="testReference" index="2D3M1q" />
      </concept>
      <concept id="4849987038621055821" name="ExampleLanguage.structure.AnotherConcept" flags="ng" index="2D3Mre">
        <child id="8684409505263942891" name="text" index="2_Nki5" />
      </concept>
    </language>
  </registry>
  <node concept="2D3Coc" id="4deBofEgBWG">
    <ref role="2D3M1q" node="4deBofEgCh2" />
  </node>
  <node concept="2D3Mre" id="4deBofEgCh2">
    <node concept="19SGf9" id="7y5e6Vti7XH" role="2_Nki5">
      <node concept="19SUe$" id="7y5e6Vti7XI" role="19SJt6">
        <property role="19SUeA" value="some text here ...  " />
      </node>
    </node>
  </node>
</model>

