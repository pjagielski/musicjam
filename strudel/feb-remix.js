// @title feb remix @by me

register('rlpf', (x, pat) => { return pat.lpf(pure(x).mul(12).pow(4)) })

await samples('github:mistipher/studel-beats')

setCpm(130/4)

let [drums, lead, arp, bass] = [1,1,1,1]

x: s("riffin/2")
   .fit()
   .seg(8)
   // .slice(8, "[0 [1 6] 2 [3 7]]")
   // .slice(8, "[0 1 2 3]")
   .slice(8, "<[0 6 2 3] [0 6 2 [3 7]]>")
   .struct("{x - - x - - x - }%16")
   // .distort(1.25)
   // .dec(0.75)
   // .delay(".5:.5")
   .postgain(.65)
   .mask(drums)


// let chords = ["D","G","Bm","A"]
let chords = "<D G Bm A>"

$: note("<3@3 4 5@3 6>*2".add("[-7, -14]")).scale("d:major") 
  .seg("[8 16]")
  .struct("{x x - x - - x x - - x x x - x - }%16")
  // .s("wt_digital:2,supersaw")
  .s("supersaw")
  // .seg(irand(2).pick([16,8,12]))
  // .seg("[8 16 16]")
  .dec(.4)
  .rel(.2)
  // .rel(sine.range(.2, .15).slow(32))
  .diode("2.5:0.7")
  .rlpf(sine.range(.5, .7).slow(8))
  .lpq(5)
  // .o(2)
  .postgain(0.5)
  .color("red")
  ._punchcard({width: 1500, height: 200})
  .mask(bass)


$: n("<[0,2] [0,-1]>")
  .seg(16)
  // .seg("[<16 ~> 8 ~]")
  // .struct("{x x - x - - x x - - x x x - x - }%16")
  .mask("[1 0 0 1 0 0 1 0]*2")
  .anchor("d4")
  .chord(chords)
  .voicing()
  .sound("supersaw")
  .room(1).roomsize(2)
  // .delay(".25")
  .decay(slider(1.487,0.1,2))
  // .release(.5)
  .transpose("[0, 12]")
  .lpf(slider(5834.8, 100, 6000))
  // .lpenv(.7)
  // .lpq(sine.range(5, 15))
  .diode(0.75)
  .color("green")
  ._punchcard({width: 1500, height: 200})
  .mask(lead)

$: 
  n("[0 1 2 3 3 2 1 0]*2")
  // n("[0 1 0 2]*2")
  // .seg("[8 16 16]")
  // .mask("<1 0 1 1>*4")
  .anchor("d3")
  .mode("root")
  .chord(chords)
  .voicing()
  .s("supersaw")
  .struct("{x x - x - - x x - - x x x - x - }%16")
  .sometimes(trans("-12"))
  // .seg(irand(2).pick([16,8,12]))
  // .mask("[1 1 1 0 1]*4") 
  // .fm(2).fmenv(5)
  // .transpose("[0, 12]")
  .diode("2.5:0.4")
  // .distort(sine.range(1.5, 2))
  .decay(0.2)
  // .release(0.2)
  // .o(2)
  .lpf(slider(1892.2, 100, 3000))
  .lpenv(1)
  .lpq(15)
  .color("cyan")
 ._punchcard({width: 1500, height: 200})
  .mask(arp)

$: stack(
    s("bd(3,8,5)").diode(1.2).gain(1.0).lpf(2000).duck("3").duckdepth(0.8),
    // s("[bd]*4").diode(1.2),
    s("[~  sd]*2").diode(1.5).room(.5).gain(1.25),
    s("hh*16").gain("[0.2 0.1]*8"),
    s("[~ oh ~ oh]*2").gain(0.45).rel(0.1).dec(0.2)
  )
 ._pianoroll()
  
  .bank("rolandtr707")
  .mask(drums)
