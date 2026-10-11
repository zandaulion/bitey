(async () => {
  // Synthetic store-screenshot data for the debug app on the test phone:
  // a made-up person, five weeks of weigh-ins and ordinary meals.
  const { localApi } = await import('/local-api.js');
  const api = (path, method, body) => localApi(path, { method, body: JSON.stringify(body) });
  const day = (offset) => {
    const d = new Date(); d.setDate(d.getDate() - offset);
    return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
  };
  let seed = 7;
  const rnd = () => (seed = (seed * 16807) % 2147483647) / 2147483647;
  const F = {
    oats: ['Oatmeal with milk', 0.9, 0.035, 0.02, 0.14, 0.015],
    banana: ['Banana', 0.89, 0.011, 0.003, 0.23, 0.026],
    yogurt: ['Greek yogurt', 0.97, 0.09, 0.05, 0.04, 0],
    berries: ['Blueberries', 0.57, 0.007, 0.003, 0.145, 0.024],
    wrap: ['Chicken salad wrap', 1.9, 0.11, 0.07, 0.2, 0.02],
    soup: ['Lentil soup', 0.6, 0.04, 0.015, 0.08, 0.02],
    bread: ['Wholegrain bread', 2.5, 0.1, 0.035, 0.43, 0.07],
    salmon: ['Baked salmon', 2.06, 0.22, 0.13, 0, 0],
    rice: ['Basmati rice', 1.3, 0.027, 0.003, 0.28, 0.004],
    broccoli: ['Steamed broccoli', 0.35, 0.024, 0.004, 0.07, 0.03],
    pasta: ['Spaghetti bolognese', 1.5, 0.07, 0.05, 0.19, 0.02],
    salad: ['Mixed green salad', 0.2, 0.013, 0.002, 0.035, 0.02],
    omelette: ['Omelette', 1.54, 0.11, 0.12, 0.01, 0],
    apple: ['Apple', 0.52, 0.003, 0.002, 0.14, 0.024],
    nuts: ['Mixed nuts', 6, 0.2, 0.5, 0.2, 0.07],
    coffee: ['Latte', 0.55, 0.033, 0.02, 0.05, 0]
  };
  let n = 0;
  const item = (k, grams, source = 'manual') => {
    const [name, calories, protein, fat, carbs, fiber] = F[k];
    const g = Math.round(grams * (0.9 + rnd() * 0.2));
    return { id: `s${++n}`, name, grams: g, per: { calories, protein, fat, carbs, fiber }, source };
  };
  const meals = [
    ['breakfast', () => [item('oats', 250), item('banana', 120)]],
    ['breakfast', () => [item('yogurt', 170), item('berries', 80), item('coffee', 250)]],
    ['breakfast', () => [item('omelette', 150), item('bread', 60)]],
    ['lunch', () => [item('wrap', 260, 'text')]],
    ['lunch', () => [item('soup', 350), item('bread', 50)]],
    ['dinner', () => [item('salmon', 150, 'photo'), item('rice', 180, 'photo'), item('broccoli', 120, 'photo')]],
    ['dinner', () => [item('pasta', 380), item('salad', 100)]],
    ['snack', () => [item('apple', 180)]],
    ['snack', () => [item('nuts', 30)]]
  ];
  const pick = (meal) => {
    const options = meals.filter(([m]) => m === meal);
    return options[Math.floor(rnd() * options.length)][1]();
  };

  await api('/api/profile', 'PUT', { heightCm: 172, birthYear: 1987, sex: 'female', activity: 'light', diet: 'omnivore', dietaryGoal: 'balanced' });
  for (let d = 35; d >= 0; d--) {
    if (d % 2 === 0 || d < 5) {
      const kg = Math.round((74.2 - (35 - d) * 0.045 + (rnd() - 0.5) * 0.6) * 10) / 10;
      await api('/api/weights', 'PUT', { day: day(d), kg });
    }
    const plan = d === 0 ? ['breakfast', 'lunch', 'snack'] : ['breakfast', 'lunch', 'dinner', ...(rnd() > 0.4 ? ['snack'] : [])];
    for (const meal of plan) {
      const items = d === 0
        ? (meal === 'breakfast' ? [item('yogurt', 170), item('berries', 80), item('coffee', 250)]
          : meal === 'lunch' ? [item('wrap', 260, 'text')] : [item('apple', 180)])
        : pick(meal);
      await api('/api/entries', 'POST', { day: day(d), meal, items, portionSource: 'estimated', note: '' });
    }
  }
  return 'seeded';
})()
