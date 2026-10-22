import { buildOffice } from '../brain/office.mjs'
import { writeFileSync } from 'node:fs'
const blocks = [
  { type: 'h1', text: 'Quarterly review' },
  { type: 'p', text: 'Revenue & costs grew <fast> "quoted" it\'s fine.' },
  { type: 'bullet', text: 'Sales up 12%' },
  { type: 'bullet', text: 'Costs flat' },
  { type: 'h2', text: 'Numbers' },
  { type: 'table', rows: [['Region', 'Q3', 'Q4'], ['North', '120', '140'], ['South', '95', '110']] },
  { type: 'p', text: 'Ek line Hinglish: ye test hai. Café — done ✓' },
]
for (const f of ['docx', 'pdf']) writeFileSync(`out.${f}`, buildOffice(f, { title: 'Test Report', blocks }).bytes)
writeFileSync('out.xlsx', buildOffice('xlsx', { sheets: [
  { name: 'Sales Q3', rows: [['Item', 'Qty', 'Price', 'Total'], ['Pen', 3, 10, '=B2*C2'], ['Book', 2, 250, '=B3*C3'], ['Sum', null, null, '=SUM(D2:D3)']] },
  { name: 'Notes', rows: [['Note'], ['hello & <world>']] },
] }).bytes)
writeFileSync('out.pptx', buildOffice('pptx', { title: 'Deck', slides: [
  { title: 'Intro', bullets: ['Point one', 'Point & two'] },
  { title: 'Second', bullets: [] },
  { title: 'Third', bullets: ['x', 'y', 'z'] },
] }).bytes)
console.log('generated')
